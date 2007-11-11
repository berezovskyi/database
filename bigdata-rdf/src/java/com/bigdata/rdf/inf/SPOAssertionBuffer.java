/**

Copyright (C) SYSTAP, LLC 2006-2007.  All rights reserved.

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
 * Created on Apr 13, 2007
 */

package com.bigdata.rdf.inf;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.bigdata.rdf.spo.ISPOAssertionBuffer;
import com.bigdata.rdf.spo.ISPOFilter;
import com.bigdata.rdf.spo.SPO;
import com.bigdata.rdf.store.AbstractTripleStore;
import com.bigdata.util.concurrent.DaemonThreadFactory;

/**
 * A buffer for {@link SPO}s and optional {@link Justification}s that is
 * flushed on overflow into a backing {@link AbstractTripleStore}.
 * 
 * @todo make this thread-safe to support parallel execution of rules writing on
 *       the same buffer.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class SPOAssertionBuffer extends AbstractSPOBuffer implements ISPOAssertionBuffer {
    
    /**
     * The array in which the optional {@link Justification}s are stored.
     */
    final private Justification[] justifications;
    
    /**
     * The #of justifications currently in {@link #justifications}
     */
    private int numJustifications;

    public int getJustificationCount() {
        
        return numJustifications;
        
    }
    
    /**
     * true iff the Truth Maintenance strategy requires that we store
     * {@link Justification}s for entailments.
     */
    protected final boolean justify;
        
    /**
     * Create a buffer.
     * 
     * @param store
     *            The database into which the terms and statements will be
     *            inserted.
     * @param filter
     *            Option filter. When present statements matched by the filter
     *            are NOT retained by the {@link SPOAssertionBuffer} and will
     *            NOT be added to the <i>store</i>.
     * @param capacity
     *            The maximum {@link SPO}s that the buffer can hold before it
     *            is {@link #flush()}ed.
     * @param justified
     *            true iff the Truth Maintenance strategy requires that we store
     *            {@link Justification}s for entailments.
     */
    public SPOAssertionBuffer(AbstractTripleStore store, ISPOFilter filter,
            int capacity, boolean justified) {
        
        super(store,filter,capacity);

        if(store==null) throw new IllegalArgumentException();
        
        this.justify = justified;

        justifications = justified ? new Justification[capacity] : null;
        
    }
        
    /**
     * Returns true iff there is no more space remaining in the buffer. Under
     * those conditions adding another statement to the buffer could cause an
     * overflow.
     * 
     * @return True if the buffer might overflow if another statement were
     *         added.
     */
    protected boolean nearCapacity() {

        if(super.nearCapacity()) return true;
        
        if (numJustifications + 1 > capacity) {

            // would overflow the justification[].
            
            return true;
            
        }
        
        return false;
        
    }
     
    /**
     * A service used to write statements and justifications at the same time.
     */
    private ExecutorService indexWriteService = Executors.newFixedThreadPool(2,
            DaemonThreadFactory.defaultThreadFactory());

    public int flush() {

        if (isEmpty()) return 0;

        final int n;
        
        log.info("numStmts=" + numStmts+", numJustifications="+numJustifications);

        final long begin = System.currentTimeMillis();
        
        if (numJustifications == 0) {
            
            // batch insert statements into the store.
            n = store.addStatements(stmts, numStmts);

        } else {
            
            /*
             * Use a thread pool to write out the statement and the
             * justifications concurrently. This drammatically reduces the
             * latency when also writing justifications.
             */

            List<Callable<Long>> tasks = new ArrayList<Callable<Long>>(2);
            
            /*
             * Note: we reject using the filter before stmts or
             * justifications make it into the buffer so we do not need to
             * apply the filter again here.
             */
           
            // set as a side-effect.
            AtomicInteger nwritten = new AtomicInteger();
            
            tasks.add(new StatementWriter(nwritten));
            
            tasks.add(new JustificationWriter());
            
            final List<Future<Long>> futures;
            final long elapsed_SPO;
            final long elapsed_JST;
            
            try {

                futures = indexWriteService.invokeAll( tasks );

                elapsed_SPO = futures.get(0).get();
                elapsed_JST = futures.get(1).get();

            } catch(InterruptedException ex) {
                
                throw new RuntimeException(ex);
                
            } catch(ExecutionException ex) {
            
                throw new RuntimeException(ex);
            
            }

            log.info("stmts=" + elapsed_SPO + "ms, justs=" + elapsed_JST + "ms");
            
            n = nwritten.get();
            
        } // end else
        
        /*
         * reset the buffer.
         */

        long elapsed = System.currentTimeMillis() - begin;

        log.info("Wrote " + n + " statements "
                + (justify ? " with justifications" : "") + " in " + elapsed
                + "ms");

        numStmts = numJustifications = 0;

        return n;

    }
    
    private class StatementWriter implements Callable<Long>{

        public final AtomicInteger nwritten;
        
        public StatementWriter(AtomicInteger nwritten) {
            
            this.nwritten = nwritten;
            
        }
        
        public Long call() throws Exception {
            
            final long begin = System.currentTimeMillis();
            
            nwritten.addAndGet(store.addStatements(stmts, numStmts));
            
            final long elapsed = System.currentTimeMillis() - begin;
            
            return elapsed;

        }
        
    }
    
    private class JustificationWriter implements Callable<Long>{

        public Long call() throws Exception {
            
            final long begin = System.currentTimeMillis();
            
            store.addJustifications(justifications, numJustifications);
            
            final long elapsed = System.currentTimeMillis() - begin;
            
            return elapsed;

        }
        
    }
    
    /**
     * When the buffer is {@link #nearCapacity()} the statements in the buffer
     * will be flushed into the backing store.
     * 
     * @see #nearCapacity()
     * @see #flush()
     */
    public boolean add( SPO stmt, Justification justification) {
        
        if (!super.add(stmt)) {

            /*
             * Note: Do not store statements (or justifications) matched by the
             * filter.
             */

            if(DEBUG) {
                
                log.debug("(filtered out)"
                        + "\n"
                        + (justification == null ? stmt.toString(store)
                                : justification.toString(store)));
                
            }

            return false;
            
        }
        
        /*
         * When justification is required the rule MUST supply a justification.
         * 
         * When it is NOT required then the rule SHOULD NOT supply a
         * justification.
         */

        assert justify ? justification != null : justification == null;

        if(justify) { 
            
            justifications[numJustifications++] = justification;
            
            if (DEBUG) {
                
                /*
                 * Note: If [store] is a TempTripleStore then this will NOT be able
                 * to resolve the terms from the ids (since the lexicon is only in
                 * the database).
                 */

                log.debug("\n"
                        + (justification == null ? stmt.toString(store)
                                : justification.toString(store)));
            
            }

        }

        return true;
        
    }
    
    /**
     * @todo also dump the justifications.
     */
    public void dump(AbstractTripleStore store) {

        System.err.println("numJusts=" + numJustifications);

        super.dump(store);
                
    }
    
}
