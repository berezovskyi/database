/**

Copyright (C) SYSTAP, LLC 2006-2012.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
/*
 * Created on Mar 17, 2012
 */

package com.bigdata.rdf.sparql.ast.eval;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.query.GraphQueryResult;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.query.UpdateExecutionException;
import org.openrdf.query.algebra.StatementPattern.Scope;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.RDFParserFactory;
import org.openrdf.rio.RDFParserRegistry;
import org.openrdf.rio.helpers.RDFHandlerBase;
import org.openrdf.sail.SailException;

import com.bigdata.bop.BOp;
import com.bigdata.bop.Constant;
import com.bigdata.bop.IBindingSet;
import com.bigdata.bop.IVariable;
import com.bigdata.bop.NV;
import com.bigdata.bop.PipelineOp;
import com.bigdata.bop.Var;
import com.bigdata.bop.bindingSet.ListBindingSet;
import com.bigdata.bop.engine.IRunningQuery;
import com.bigdata.bop.rdf.update.ChunkedResolutionOp;
import com.bigdata.bop.rdf.update.CommitOp;
import com.bigdata.bop.rdf.update.InsertStatementsOp;
import com.bigdata.bop.rdf.update.ParseOp;
import com.bigdata.bop.rdf.update.RemoveStatementsOp;
import com.bigdata.rdf.internal.IV;
import com.bigdata.rdf.model.BigdataURI;
import com.bigdata.rdf.sail.BigdataSail;
import com.bigdata.rdf.sail.BigdataSail.BigdataSailConnection;
import com.bigdata.rdf.sparql.ast.ASTContainer;
import com.bigdata.rdf.sparql.ast.AbstractGraphDataUpdate;
import com.bigdata.rdf.sparql.ast.AddGraph;
import com.bigdata.rdf.sparql.ast.ConstantNode;
import com.bigdata.rdf.sparql.ast.ConstructNode;
import com.bigdata.rdf.sparql.ast.CopyGraph;
import com.bigdata.rdf.sparql.ast.CreateGraph;
import com.bigdata.rdf.sparql.ast.DeleteInsertGraph;
import com.bigdata.rdf.sparql.ast.DropGraph;
import com.bigdata.rdf.sparql.ast.JoinGroupNode;
import com.bigdata.rdf.sparql.ast.LoadGraph;
import com.bigdata.rdf.sparql.ast.MoveGraph;
import com.bigdata.rdf.sparql.ast.ProjectionNode;
import com.bigdata.rdf.sparql.ast.QuadData;
import com.bigdata.rdf.sparql.ast.QueryRoot;
import com.bigdata.rdf.sparql.ast.QueryType;
import com.bigdata.rdf.sparql.ast.StaticAnalysis;
import com.bigdata.rdf.sparql.ast.TermNode;
import com.bigdata.rdf.sparql.ast.Update;
import com.bigdata.rdf.sparql.ast.UpdateRoot;
import com.bigdata.rdf.sparql.ast.UpdateType;
import com.bigdata.rdf.sparql.ast.VarNode;
import com.bigdata.rdf.sparql.ast.optimizers.DefaultOptimizerList;
import com.bigdata.rdf.sparql.ast.optimizers.IASTOptimizer;
import com.bigdata.rdf.spo.ISPO;
import com.bigdata.rdf.store.AbstractTripleStore;
import com.bigdata.rdf.store.BD;

/**
 * Class handles SPARQL update query plan generation.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 * 
 *          TODO When translating, the notion that we might translate either
 *          incrementally or all operations in the sequence at once is related
 *          to the notion of interactive evaluation which would help to enable
 *          the RTO.
 * 
 *          FIXME Where are the DataSet(s) for the update operations in the
 *          sequence coming from? I assume that they will be attached to each
 *          {@link Update}.
 * 
 *          FIXME DELETE DATA with triples addresses the "defaultGraph". Since
 *          that is the RDF merge of all named graphs for the bigdata quads
 *          mode, the operation needs to be executed separately for each
 *          {@link ISPO} in which the context position is not bound. For such
 *          {@link ISPO}s, we need to remove everything matching the (s,p,o) in
 *          the quads store (c is unbound).
 *          <p>
 *          This is not being handled coherently right now. We are handling this
 *          by binding [c] to the nullGraph when removing data, but it looks
 *          like some triples are being allowed in without [c] being bound.
 */
public class AST2BOpUpdate extends AST2BOpUtility {

    private static final Logger log = Logger.getLogger(AST2BOpUpdate.class);

    /**
     * When <code>true</code>, convert the SPARQL UPDATE into a physical
     * operator plan and execute it on the query engine. When <code>false</code>
     * , the UPDATE is executed using the {@link BigdataSail} API.
     * 
     * FIXME By coming in through the SAIL, we automatically pick up truth
     * maintenance and related logics. All of that needs to be integrated into
     * the generated physical operator plan before we can run updates on the
     * query engine. However, there will be advantages to running updates on the
     * query engine, including declarative control of parallelism, more
     * similarity for code paths between a single machine and cluster
     * deployments, and a unified operator model for query and update
     * evaluation.
     */
    private final static boolean runOnQueryEngine = false;
    
    /**
     * 
     */
    public AST2BOpUpdate() {
        super();
    }
    
    /**
     * The s,p,o, and c variable names used for binding sets which model
     * {@link Statement}s.
     */
    private static final Var<?> s = Var.var("s"), p = Var.var("p"), o = Var
            .var("o"), c = Var.var("c");

    /**
     * Convert the query (generates an optimized AST as a side-effect).
     * 
     * TODO Top-level optimization pass over the {@link UpdateRoot}. We have to
     * resolve IVs, etc., etc. See if we can just use the
     * {@link DefaultOptimizerList} and modify the {@link IASTOptimizer}s that
     * we need to work against update as well as query. (We will need a common
     * API for the WHERE clause). We will need to expand the AST optimizer test
     * suite for this.
     * 
     * TODO Focus on getting the assertion/retraction patterns right in the
     * different database modes).
     */
    protected static void optimizeUpdateRoot(final AST2BOpUpdateContext context) {

        final ASTContainer astContainer = context.astContainer;

        // TODO Clear the optimized AST.
        // astContainer.clearOptimizedUpdateAST();

        /*
         * TODO Build up the optimized AST for the UpdateRoot for each Update to
         * be executed. Maybe do this all up front before we run anything since
         * we might reorder or regroup some operations (e.g., parallelized LOAD
         * operations, parallelized INSERT data operations, etc).
         */
        final UpdateRoot updateRoot = astContainer.getOriginalUpdateAST();

        /*
         * Evaluate each update operation in the optimized UPDATE AST in turn.
         */
        for (Update op : updateRoot) {

        }

    }

    /**
     * Generate physical plan for the update operations (attached to the AST as
     * a side-effect).
     * 
     * @throws Exception
     */
    protected static PipelineOp convertUpdate(final AST2BOpUpdateContext context)
            throws Exception {

        final ASTContainer astContainer = context.astContainer;

        // FIXME Change this to the optimized AST.
        final UpdateRoot updateRoot = astContainer.getOriginalUpdateAST();

        /*
         * Evaluate each update operation in the optimized UPDATE AST in turn.
         */
        PipelineOp left = null;
        for (Update op : updateRoot) {

            left = convertUpdateSwitch(left, op, context);

        }

        /*
         * Commit.
         * 
         * Note: Not required on cluster.
         * 
         * Note: Not required unless the end of the UpdateRoot or we desired a
         * checkpoint on the sequences of operations.
         * 
         * TODO The commit really can not happen until the update plan(s) were
         * known to execute successfully. We could do that with an AT_ONCE
         * annotation on the CommitOp or we could just invoke commit() at
         * appropriate checkpoints in the UPDATE operation.
         */
        if (!context.isCluster()) {
            if (runOnQueryEngine) {
                left = new CommitOp(leftOrEmpty(left), NV.asMap(
                        //
                        new NV(BOp.Annotations.BOP_ID, context.nextId()),//
                        new NV(CommitOp.Annotations.TIMESTAMP, context
                                .getTimestamp()),//
                        new NV(CommitOp.Annotations.PIPELINED, false)//
                        ));
            } else {
                context.conn.commit();
            }
        }

        // Set as annotation on the ASTContainer.
        astContainer.setQueryPlan(left);
        
        return left;

    }

    /**
     * Method provides the <code>switch()</code> for handling the different
     * {@link UpdateType}s.
     * 
     * @param left
     * @param op
     * @param context
     * @return
     * @throws Exception
     */
    private static PipelineOp convertUpdateSwitch(PipelineOp left,
            final Update op, final AST2BOpUpdateContext context)
            throws Exception {

        final UpdateType updateType = op.getUpdateType();

        switch (updateType) {
        case Create: {
            left = convertCreateGraph(left, (CreateGraph) op, context);
            break;
        }
        case Add: {
            // Copy all statements from source to target.
            left = convertAddGraph(left, (AddGraph) op, context);
            break;
        }
        case Copy: {
            // Drop() target, then Add().
            left = convertCopyGraph(left, (CopyGraph) op, context);
            break;
            }
        case Move: {
            // Drop() target, Add(source,target), Drop(source).
            left = convertMoveGraph(left, (MoveGraph) op, context);
            break;
        }
        case Clear:
        case Drop:
            left = convertClearOrDropGraph(left, (DropGraph) op, context);
            break;
        case InsertData:
        case DeleteData:
            left = convertGraphDataUpdate(left, (AbstractGraphDataUpdate) op,
                    context);
            break;
        case Load:
            left = convertLoadGraph(left, (LoadGraph) op, context);
            break;
        case DeleteInsert:
            left = convertDeleteInsert(left, (DeleteInsertGraph) op, context);
            break;
        default:
            throw new UnsupportedOperationException("updateType=" + updateType);
        }

        return left;
        
    }

    /**
     * <pre>
     * ( WITH IRIref )?
     * ( ( DeleteClause InsertClause? ) | InsertClause )
     * ( USING ( NAMED )? IRIref )*
     * WHERE GroupGraphPattern
     * </pre>
     * 
     * @param left
     * @param op
     * @param context
     * @return
     * @throws QueryEvaluationException
     * @throws RepositoryException 
     */
    private static PipelineOp convertDeleteInsert(PipelineOp left,
            final DeleteInsertGraph op, final AST2BOpUpdateContext context)
            throws QueryEvaluationException, RepositoryException {

        if (runOnQueryEngine)
            throw new UnsupportedOperationException();

        /*
         * This models the DELETE/INSERT request as a QUERY. The data from the
         * query are fed into a handler which adds or removes the statements (as
         * appropriate) from the [conn].
         * 
         * FIXME The shortcut forms need to have AST translation to build the
         * templates.
         * 
         * DELETE WHERE QuadPattern
         */
        {

            /*
             * Create a new query using the WHERE clause.
             */
            final JoinGroupNode whereClause = new JoinGroupNode(
                    op.getWhereClause());

            final QueryRoot queryRoot = new QueryRoot(QueryType.SELECT);

            queryRoot.setWhereClause(whereClause);

            /*
             * Setup the PROJECTION for the new query.
             * 
             * TODO retainAll() for only those variables used in the template
             * for the InsertClause or RemoveClause (less materialization, more
             * efficient).
             * 
             * TODO Actually, what we really want to do is to CONSTRUCT the
             * Statements to be INSERTED or REMOVED. Thus, if we extend
             * CONSTRUCT to handle quads (first in the operator and then in the
             * SPARQL syntax) then we could set this up as a CONSTRUCT query and
             * then feed the result into a handler which adds or removed the
             * statements on the [conn].
             */
            {

                final StaticAnalysis sa = new StaticAnalysis(queryRoot);

                final Set<IVariable<?>> projectedVars = sa
                        .getMaybeProducedBindings(whereClause,
                                new LinkedHashSet<IVariable<?>>()/* vars */,
                                true/* recursive */);

                final ProjectionNode projection = new ProjectionNode();

                for (IVariable<?> var : projectedVars) {

                    projection.addProjectionVar(new VarNode(var.getName()));

                }

                queryRoot.setProjection(projection);
                
            }

            final ASTContainer astContainer = new ASTContainer(queryRoot);

            /*
             * FIXME Data set and defaultContet.
             */
            final Resource[] contexts = new Resource[] { BD.NULL_GRAPH };

             final QuadData insertClause = op.getInsertClause();

            final QuadData deleteClause = op.getDeleteClause();

            if (insertClause == null && deleteClause == null) {
                // Must have at least one or the other.
                throw new UnsupportedOperationException();
            }

            // Just the insert clause.
            final boolean isInsertOnly = insertClause != null
                    && deleteClause == null;

            // Just the delete clause.
            final boolean isDeleteOnly = insertClause == null
                    && deleteClause != null;

            // Both the delete clause and the insert clause.
            final boolean isDeleteInsert = insertClause != null
                    && deleteClause != null;
            
            /*
             * Run the WHERE clause.
             */

            if (isDeleteInsert) {
                
                /*
                 * DELETE + INSERT.
                 * 
                 * FIXME Simple CONSTRUCT semantics are not enough for this
                 * case. We need to process each solution by feeding into the
                 * delete template and then feeding it into the insert template.
                 * Both templates are quads (not just triples). We might need to
                 * fully buffer the solutions and replay them so as to run the
                 * DELETEs before the INSERTs, or we might even need to run the
                 * WHERE clause twice - once to do the DELETEs and then once
                 * more to do the INSERTs. This depends on the isolation
                 * semantics for the operation with respect to itself.
                 */
                
                throw new UnsupportedOperationException();

            } else {

                final QuadData quadData = insertClause == null ? deleteClause
                        : insertClause;
                
                final ConstructNode template = quadData.flatten();
                
                // Set the CONSTRUCT template (quads patterns).
                queryRoot.setConstruct(template);
                
                // Run as a CONSTRUCT query.
                final GraphQueryResult result = ASTEvalHelper
                        .evaluateGraphQuery(context.db, astContainer, null/* bindingSets */);

                try {

                    if (isInsertOnly) {

                        context.conn.add(result, contexts);

                    } else if (isDeleteOnly) {

                        context.conn.remove(result, contexts);

                    }

                } finally {

                    result.close();

                }

            }

        }

        return null;
        
    }

    /**
     * Copy all statements from source to target.
     * 
     * @param left
     * @param op
     * @param context
     * @return
     * @throws RepositoryException 
     */
    private static PipelineOp convertAddGraph(PipelineOp left,
            final AddGraph op, final AST2BOpUpdateContext context)
            throws RepositoryException {

        if (runOnQueryEngine)
            throw new UnsupportedOperationException();

        copyStatements(//
                context, //
                op.isSilent(), //
                (BigdataURI) op.getSourceGraph().getValue(), //
                (BigdataURI) op.getTargetGraph().getValue());

        return null;
    }

    /**
     * Copy all statements from the sourceGraph to the targetGraph.
     */
    private static void copyStatements(final AST2BOpUpdateContext context,
            final boolean silent, final BigdataURI sourceGraph,
            final BigdataURI targetGraph) throws RepositoryException {

        final RepositoryResult<Statement> result = context.conn.getStatements(
                null/* s */, null/* p */, null/* o */,
                context.isIncludeInferred(), new Resource[] { sourceGraph });
        try {

            context.conn.add(result, new Resource[] { targetGraph });

        } finally {

            result.close();

        }

    }

    /**
     * Drop() target, Add(source,target), Drop(source).
     * 
     * @param left
     * @param op
     * @param context
     * @return
     * @throws RepositoryException
     */
    private static PipelineOp convertMoveGraph(PipelineOp left,
            final MoveGraph op, final AST2BOpUpdateContext context)
            throws RepositoryException {

        if (runOnQueryEngine)
            throw new UnsupportedOperationException();

        final BigdataURI sourceGraph = (BigdataURI) op.getSourceGraph()
                .getValue();

        if (sourceGraph == null) {
            // Do NOT allow this when the targetGraph is not declared.
            throw new AssertionError();
        }

        final BigdataURI targetGraph = (BigdataURI) op.getTargetGraph()
                .getValue();

        if (targetGraph == null) {
            // Do NOT allow this when the targetGraph is not declared.
            throw new AssertionError();
        }

        clearGraph(targetGraph, null/* scope */, context);

        copyStatements(context, op.isSilent(), sourceGraph, targetGraph);

        clearGraph(sourceGraph, null/* scope */, context);

        return null;
        
    }

    /**
     * Drop() target, then Add().
     * 
     * @param left
     * @param op
     * @param context
     * @return
     * @throws RepositoryException 
     */
    private static PipelineOp convertCopyGraph(PipelineOp left,
            final CopyGraph op, final AST2BOpUpdateContext context)
            throws RepositoryException {

        if (runOnQueryEngine)
            throw new UnsupportedOperationException();

        final BigdataURI sourceGraph = (BigdataURI) op.getSourceGraph()
                .getValue();

        if (sourceGraph == null) {
            // Do NOT allow this when the targetGraph is not declared.
            throw new AssertionError();
        }

        final BigdataURI targetGraph = (BigdataURI) op.getTargetGraph()
                .getValue();

        if (targetGraph == null) {
            // Do NOT allow this when the targetGraph is not declared.
            throw new AssertionError();
        }

        clearGraph(targetGraph, null/* scope */, context);

        copyStatements(context, op.isSilent(), sourceGraph, targetGraph);

        return null;
    }

    /**
     * <pre>
     * LOAD ( SILENT )? IRIref_from ( INTO GRAPH IRIref_to )?
     * </pre>
     * 
     * @param left
     * @param op
     * @param context
     * @return
     * @throws Exception
     */
    private static PipelineOp convertLoadGraph(PipelineOp left,
            final LoadGraph op, final AST2BOpUpdateContext context)
            throws Exception {

        if (!runOnQueryEngine) {

            final AtomicLong nmodified = new AtomicLong();

            final String urlStr = op.getSourceGraph().getValue().stringValue();

            try {

                final URL sourceURL = new URL(urlStr);

                final BigdataURI defaultContext = (BigdataURI) (op
                        .getTargetGraph() == null ? null : op.getTargetGraph()
                        .getValue());

                doLoad(context.conn.getSailConnection(), sourceURL,
                        defaultContext, nmodified);

            } catch (Throwable t) {

                final String msg = "Could not load: url=" + urlStr + ", cause="
                        + t;

                if (op.isSilent()) {
                 
                    log.warn(msg);
                    
                } else {
                    
                    throw new RuntimeException(msg, t);
                    
                }

            }

            return null;
            
        }
        
        /*
         * Parse the file.
         * 
         * Note: After the parse step, the remainder of the steps are just like
         * INSERT DATA.
         */
        {

            final Map<String, Object> anns = new HashMap<String, Object>();

            anns.put(BOp.Annotations.BOP_ID, context.nextId());

            // required.
            anns.put(ParseOp.Annotations.SOURCE_URI, op.getSourceGraph()
                    .getValue());

            if(op.isSilent())
                anns.put(ParseOp.Annotations.SILENT, true);

            // optional.
            if (op.getTargetGraph() != null)
                anns.put(ParseOp.Annotations.TARGET_URI, op.getTargetGraph());

            // required.
            anns.put(ParseOp.Annotations.TIMESTAMP, context.getTimestamp());
            anns.put(ParseOp.Annotations.RELATION_NAME,
                    new String[] { context.getNamespace() });

            /*
             * TODO 100k is the historical default for the data loader. We
             * generally want to parse a lot of data at once and vector it in
             * big chunks. However, we could have a lot more parallelism with
             * the query engine. So, if there are multiple source URIs to be
             * loaded, then we might want to reduce the vector size (or maybe
             * not, probably depends on the JVM heap).
             */
            anns.put(ParseOp.Annotations.CHUNK_CAPACITY, 100000);

            left = new ParseOp(leftOrEmpty(left), anns);

        }

        /*
         * Append the pipeline operations to add/resolve IVs against the lexicon
         * and insert/delete statemetns.
         */
        left = addInsertOrDeleteDataPipeline(left, true/* insert */, context);

        /*
         * Execute the update.
         */
        executeUpdate(left, null/* bindingSets */, context);

        // Return null since pipeline was evaluated.
        return null;
        
    }

    /**
     * Parse and load a document.
     * 
     * @param conn
     * @param sourceURL
     * @param defaultContext
     * @param nmodified
     * @return
     * @throws IOException
     * @throws RDFHandlerException
     * @throws RDFParseException
     * 
     *             TODO See {@link ParseOp} for a significantly richer pipeline
     *             operator which will parse a document. However, this method is
     *             integrated into all of the truth maintenance mechanisms in
     *             the Sail and is therefore easier to place into service.
     */
    private static void doLoad(final BigdataSailConnection conn,
            final URL sourceURL, final URI defaultContext,
            final AtomicLong nmodified) throws IOException, RDFParseException,
            RDFHandlerException {

        // Use the default context if one was given and otherwise
        // the URI from which the data are being read.
        final Resource defactoContext = defaultContext == null ? new URIImpl(
                sourceURL.toExternalForm()) : defaultContext;

        URLConnection hconn = null;
        try {

            hconn = sourceURL.openConnection();
            if (hconn instanceof HttpURLConnection) {
                ((HttpURLConnection) hconn).setRequestMethod("GET");
            }
            hconn.setDoInput(true);
            hconn.setDoOutput(false);
            hconn.setReadTimeout(0);// no timeout? http param?

            /*
             * There is a request body, so let's try and parse it.
             */

            final String contentType = hconn.getContentType();

            RDFFormat format = RDFFormat.forMIMEType(contentType);

            if (format == null) {
                // Try to get the RDFFormat from the URL's file path.
                format = RDFFormat.forFileName(sourceURL.getFile());
            }
            
            if (format == null) {
                throw new RuntimeException(
                        "Content-Type not recognized as RDF: "
                                + contentType);
            }

            final RDFParserFactory rdfParserFactory = RDFParserRegistry
                    .getInstance().get(format);

            if (rdfParserFactory == null) {
                throw new RuntimeException(
                        "Parser not found: Content-Type=" + contentType);
            }

            final RDFParser rdfParser = rdfParserFactory
                    .getParser();

            rdfParser.setValueFactory(conn.getTripleStore()
                    .getValueFactory());

            rdfParser.setVerifyData(true);

            rdfParser.setStopAtFirstError(true);

            rdfParser
                    .setDatatypeHandling(RDFParser.DatatypeHandling.IGNORE);

            rdfParser.setRDFHandler(new AddStatementHandler(conn, nmodified,
                    defactoContext));

            /*
             * Run the parser, which will cause statements to be
             * inserted.
             */

            rdfParser.parse(hconn.getInputStream(), sourceURL
                    .toExternalForm()/* baseURL */);

        } finally {

            if (hconn instanceof HttpURLConnection) {
                /*
                 * Disconnect, but only after we have loaded all the
                 * URLs. Disconnect is optional for java.net. It is a
                 * hint that you will not be accessing more resources on
                 * the connected host. By disconnecting only after all
                 * resources have been loaded we are basically assuming
                 * that people are more likely to load from a single
                 * host.
                 */
                ((HttpURLConnection) hconn).disconnect();
            }

        }
        
    }
    
    /**
     * Helper class adds statements to the sail as they are visited by a parser.
     */
    private static class AddStatementHandler extends RDFHandlerBase {

        private final BigdataSailConnection conn;
        private final AtomicLong nmodified;
        private final Resource[] defaultContexts;

        public AddStatementHandler(final BigdataSailConnection conn,
                final AtomicLong nmodified, final Resource defaultContext) {
            this.conn = conn;
            this.nmodified = nmodified;
            final boolean quads = conn.getTripleStore().isQuads();
            if (quads && defaultContext != null) {
                // The default context may only be specified for quads.
                this.defaultContexts = new Resource[] { defaultContext };
            } else {
                this.defaultContexts = new Resource[0];
            }
        }

        public void handleStatement(final Statement stmt)
                throws RDFHandlerException {

            try {

                conn.addStatement(//
                        stmt.getSubject(), //
                        stmt.getPredicate(), //
                        stmt.getObject(), //
                        (Resource[]) (stmt.getContext() == null ?  defaultContexts
                                : new Resource[] { stmt.getContext() })//
                        );

            } catch (SailException e) {

                throw new RDFHandlerException(e);

            }

            nmodified.incrementAndGet();

        }

    }

    /**
     * Note: Bigdata does not support empty graphs, so {@link UpdateType#Clear}
     * and {@link UpdateType#Drop} have the same semantics.
     * 
     * <pre>
     * DROP ( SILENT )? (GRAPH IRIref | DEFAULT | NAMED | ALL )
     * </pre>
     * 
     * @param left
     * @param op
     * @param context
     * @return
     */
    private static PipelineOp convertClearOrDropGraph(PipelineOp left,
            final DropGraph op, final AST2BOpUpdateContext context) {

        final TermNode targetGraphNode = op.getTargetGraph();

        final BigdataURI targetGraph = targetGraphNode == null ? null
                : (BigdataURI) targetGraphNode.getValue();

        final Scope scope = op.getScope();

        if(runOnQueryEngine)
            throw new UnsupportedOperationException();

        clearGraph(targetGraph,scope, context);        

        return left;
        
    }

    /**
     * Remove all statements from the target graph or the specified
     * {@link Scope}.
     * 
     * @param targetGraph
     * @param scope
     * @param context
     * 
     *            FIXME DataSet is required for {@link Scope}.
     */
    private static void clearGraph(final URI targetGraph, final Scope scope,
            final AST2BOpUpdateContext context) {

        if (targetGraph != null) {

            /*
             * Addressing a specific graph.
             */

            context.db.removeStatements(null/* s */, null/* p */, null/* o */,
                    targetGraph);

        } else if (scope != null) {

            /*
             * Addressing either the defaultGraph or the named graphs.
             * 
             * FIXME We need access to the data set for this. Where is it
             * attached on the UPDATE AST?
             */
        
            throw new UnsupportedOperationException();

        } else {  
            
            /*
             * Addressing ALL graphs.
             */

            context.db.removeStatements(null/* s */, null/* p */, null/* o */,
                    null/* c */);
            
        }

    }
    /**
     * If the graph already exists (context has at least one statement), then
     * this is an error (unless SILENT). Otherwise it is a NOP.
     * 
     * @param left
     * @param op
     * @param context
     * @return
     */
    private static PipelineOp convertCreateGraph(final PipelineOp left,
            final CreateGraph op, final AST2BOpUpdateContext context) {

        if (!op.isSilent()) {

            final IV<?, ?> c = ((CreateGraph) op).getTargetGraph().getValue()
                    .getIV();

            if (context.db.getAccessPath(null/* s */, null/* p */, null/* o */,
                    c).rangeCount(false/* exact */) != 0) {

                throw new RuntimeException("Graph exists: " + c.getValue());

            }

        }

        return left;

    }

    /**
     * <pre>
     * INSERT DATA -or- DELETE DATA
     * </pre>
     * 
     * @param left
     * @param op
     * @param context
     * @return
     * @throws Exception
     */
    private static PipelineOp convertGraphDataUpdate(PipelineOp left,
            final AbstractGraphDataUpdate op, final AST2BOpUpdateContext context)
            throws Exception {
        
        final boolean insert;
        switch (op.getUpdateType()) {
        case InsertData:
            insert = true;
            break;
        case DeleteData:
            insert = false;
            break;
        default:
            throw new UnsupportedOperationException(op.getUpdateType().name());
        }

        if (!runOnQueryEngine) {
            final ISPO[] stmts = op.getData();
            final BigdataSailConnection conn = context.conn.getSailConnection();

            for (ISPO spo : stmts) {
                final Resource s = (Resource) spo.s().getValue();
                final URI p = (URI) spo.p().getValue();
                final Value o = (Value) spo.o().getValue();
                final Resource c = (Resource) (spo.c() == null ? BD.NULL_GRAPH
                        : spo.c().getValue());
                final Resource[] contexts = (Resource[]) (c == null ? BD.NULL_GRAPH
                        : new Resource[] { c });
                if (insert) {
                    conn.addStatement(s, p, o, contexts);
                } else {
                    conn.removeStatements(s, p, o, contexts);
                }
            }
            return null;
        }
        
        /*
         * Convert the statements to be asserted or retracted into an
         * IBindingSet[].
         */
        final IBindingSet[] bindingSets;
        {

            // Note: getTargetGraph() is not defined for INSERT/DELETE DATA.
            final ConstantNode c = null;// op.getTargetGraph();

            @SuppressWarnings("rawtypes")
            IV targetGraphIV = null;

            if (c != null) {

                targetGraphIV = c.getValue().getIV();

            }

            if (targetGraphIV == null && context.isQuads()) {

                /*
                 * 
                 * TODO Extract nullGraphIV into AST2BOpUpdateContext and cache.
                 * Ideally, this should always be part of the Vocabulary and the
                 * IVCache should be set (which is always true for the
                 * vocabulary).
                 */
                final BigdataURI nullGraph = context.db.getValueFactory()
                        .asValue(BD.NULL_GRAPH);
                context.db.addTerm(nullGraph);
                targetGraphIV = nullGraph.getIV();
                targetGraphIV.setValue(nullGraph);

            }

            bindingSets = getData(op.getData(), targetGraphIV,
                    context.isQuads());

        }

        /*
         * Append the pipeline operations to add/resolve IVs against the lexicon
         * and insert/delete statemetns.
         */

        left = addInsertOrDeleteDataPipeline(left, insert, context);

        /*
         * Execute the update.
         */
        executeUpdate(left, bindingSets, context);
        
        // Return null since pipeline was evaluated.
        return null;

    }

    /**
     * @param left
     * @param b
     * @param context
     * @return
     */
    private static PipelineOp addInsertOrDeleteDataPipeline(PipelineOp left,
            final boolean insert, final AST2BOpUpdateContext context) {
        
        /*
         * Resolve/add terms against the lexicon.
         * 
         * TODO Must do SIDs support. Probably pass the database mode in as an
         * annotation. See StatementBuffer.
         */
        left = new ChunkedResolutionOp(leftOrEmpty(left), NV.asMap(
                //
                new NV(BOp.Annotations.BOP_ID, context.nextId()),//
                new NV(ChunkedResolutionOp.Annotations.TIMESTAMP, context
                        .getTimestamp()),//
                new NV(ChunkedResolutionOp.Annotations.RELATION_NAME,
                        new String[] { context.getLexiconNamespace() })//
                ));

        /*
         * Insert / remove statements.
         * 
         * Note: namespace is the triple store, not the spo relation. This is
         * because insert is currently on the triple store for historical SIDs
         * support.
         * 
         * Note: This already does TM for SIDs mode.
         * 
         * TODO This must to TM for the subject-centric text index.
         * 
         * TODO This must be able to do TM for triples+inference.
         */
        if (insert) {
            left = new InsertStatementsOp(leftOrEmpty(left), NV.asMap(
                    new NV(BOp.Annotations.BOP_ID, context.nextId()),//
                    new NV(ChunkedResolutionOp.Annotations.TIMESTAMP, context
                            .getTimestamp()),//
                    new NV(ChunkedResolutionOp.Annotations.RELATION_NAME,
                            new String[] { context.getNamespace() })//
                    ));
        } else {
            left = new RemoveStatementsOp(leftOrEmpty(left), NV.asMap(
                    new NV(BOp.Annotations.BOP_ID, context.nextId()),//
                    new NV(ChunkedResolutionOp.Annotations.TIMESTAMP, context
                            .getTimestamp()),//
                    new NV(ChunkedResolutionOp.Annotations.RELATION_NAME,
                            new String[] { context.getNamespace() })//
                    ));
        }

        return left;
    }

    /**
     * Convert an {@link ISPO}[] into an {@link IBindingSet}[].
     * 
     * @param data
     *            The {@link ISPO}[].
     * @param targetGraph
     *            The target graph (optional, but required if quads).
     * @param quads
     *            <code>true</code> iff the target {@link AbstractTripleStore}
     *            is in quads mode.
     * 
     * @return The {@link IBindingSet}[].
     * 
     *         TODO Either we need to evaluate this NOW (rather than deferring
     *         it to pipelined evaluation later) or this needs to be pumped into
     *         a hash index associated with the query plan in order to be
     *         available when there is more than one INSERT DATA or REMOVE DATA
     *         operation (or simply more than one UPDATE operation).
     *         <p>
     *         That hash index could be joined into the solutions immediate
     *         before we undertake the chunked resolution operation which then
     *         flows into the add/remove statements operation.
     *         <p>
     *         Variables in the query can not be projected into this operation
     *         without causing us to insert/delete the cross product of those
     *         variables, which has no interesting effect.
     *         <p>
     *         The advantage of running one plan per {@link Update} is that the
     *         data can be flowed naturally into the {@link IRunningQuery}.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static IBindingSet[] getData(final ISPO[] data,
            final IV<?, ?> targetGraph, final boolean quads) {

        final IBindingSet[] bsets = new IBindingSet[data.length];

        for (int i = 0; i < data.length; i++) {

            final ISPO spo = data[i];

            final IBindingSet bset = bsets[i] = new ListBindingSet();

            bset.set(s, new Constant(spo.s()));

            bset.set(p, new Constant(spo.p()));

            bset.set(o, new Constant(spo.o()));

            Constant g = null;

            if (spo.c() != null)
                g = new Constant(spo.c());

            if (quads && g == null) {

                g = new Constant(targetGraph);

            }

            if (g != null) {

                bset.set(c, g);

            }

        }

        return bsets;
    
    }

    /**
     * Execute the update plan.
     * 
     * @param left
     * @param bindingSets
     *            The source solutions.
     * @param context
     * 
     * @throws UpdateExecutionException
     */
    static private void executeUpdate(final PipelineOp left,
            IBindingSet[] bindingSets, final AST2BOpUpdateContext context)
            throws Exception {

        if (!runOnQueryEngine)
            throw new UnsupportedOperationException();
        
        if (left == null)
            throw new IllegalArgumentException();

        if(bindingSets == null) {
            bindingSets = EMPTY_BINDING_SETS;
        }
        
        if (context == null)
            throw new IllegalArgumentException();

        IRunningQuery runningQuery = null;
        try {

            // Submit update plan for evaluation.
            runningQuery = context.queryEngine.eval(left, bindingSets);

            // Wait for the update plan to complete.
            runningQuery.get();

        } finally {

            if (runningQuery != null) {
            
                // ensure query is halted.
                runningQuery.cancel(true/* mayInterruptIfRunning */);
            }
            
        }

    }

    private static final IBindingSet[] EMPTY_BINDING_SETS = new IBindingSet[0];
    
}
