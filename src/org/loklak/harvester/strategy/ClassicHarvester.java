/**
 *  ClassicHarvester
 *  Copyright 13.11.2015 by Michael Peter Christen, @0rb1t3r
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; wo even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package org.loklak.harvester.strategy;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.jetty.util.ConcurrentHashSet;
import org.loklak.api.search.SearchServlet;
import org.loklak.api.search.SuggestServlet;
import org.loklak.data.DAO;
import org.loklak.harvester.PushThread;
import org.loklak.harvester.TwitterScraper;
import org.loklak.harvester.TwitterScraper.TwitterTweet;
import org.loklak.objects.QueryEntry;
import org.loklak.objects.ResultList;
import org.loklak.objects.TwitterTimeline;
import org.loklak.objects.BasicTimeline.Order;
import org.loklak.tools.DateParser;

public class ClassicHarvester implements Harvester {

    private final static int FETCH_MIN = 20;
    private final static int HITS_LIMIT_4_QUERIES = 20;
    private final static int MAX_PENDING = 300; // this could be much larger but we don't want to cache too many of these
    private final static int MAX_HARVESTED = 10000; // just to prevent a memory leak with possible OOM after a long time we flush that cache after a while
    private final static Random random = new Random(System.currentTimeMillis());
    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    private LinkedHashSet<String> pendingQueries = new LinkedHashSet<>();
    private ConcurrentLinkedDeque<String> pendingContext = new ConcurrentLinkedDeque<>();
    private Set<String> harvestedContext = new ConcurrentHashSet<>();

    private int hitsOnBackend = 100;

    public void checkContext(TwitterTimeline tl, boolean front) {
        for (TwitterTweet tweet: tl) {
            for (String user: tweet.getMentions()) checkContext("from:" + user, front);
            for (String hashtag: tweet.getHashtags()) checkContext(hashtag, front);
        }
    }

    public void checkContext(String s, boolean front) {
        if (!front && pendingContext.size() > MAX_PENDING) return; // queue is full
        if (!harvestedContext.contains(s) && !pendingContext.contains(s)) {
            if (front) pendingContext.addFirst(s); else pendingContext.addLast(s);
        }
        while (pendingContext.size() > MAX_PENDING) pendingContext.removeLast();
        if (harvestedContext.size() > MAX_HARVESTED) harvestedContext.clear();
    }

    public int harvest() {
        String[] backend = DAO.getBackend();

        if (random.nextInt(100) != 0 && hitsOnBackend < HITS_LIMIT_4_QUERIES && pendingQueries.size() == 0 && pendingContext.size() > 0) {
            // harvest using the collected keys instead using the queries
            String q = pendingContext.removeFirst();
            harvestedContext.add(q);
            TwitterTimeline tl = TwitterScraper.search(q, Order.CREATED_AT, true, true, 400);
            if (tl == null || tl.size() == 0) return -1;

            // find content query strings and store them in the context cache
            checkContext(tl, false);
            DAO.log("retrieval of " + tl.size() + " new messages for q = " + q + ", scheduled push; pendingQueries = " + pendingQueries.size() + ", pendingContext = " + pendingContext.size() + ", harvestedContext = " + harvestedContext.size());
            return tl.size();
        }

        // load more queries if pendingQueries is empty
        if (pendingQueries.size() == 0) {
            try {
                int fetch_random = Math.min(100, Math.max(FETCH_MIN, hitsOnBackend / 10));
                ResultList<QueryEntry> rl = SuggestServlet.suggest(backend, "", "query", fetch_random * 5, "asc", "retrieval_next", DateParser.getTimezoneOffset(), null, "now", "retrieval_next", fetch_random);
                for (QueryEntry qe: rl) {
                    pendingQueries.add(qe.getQuery());
                }
                hitsOnBackend = (int) rl.getHits();
                DAO.log("got " + rl.size() + " suggestions for harvesting from " + hitsOnBackend + " in backend");
                if (hitsOnBackend == 0) {
                    // the backend does not have any new query words for this time.
                    if (pendingContext.size() == 0) {
                        // try to fill the pendingContext using a matchall-query from the cache
                        // http://loklak.org/api/search.json?source=cache&q=
                        TwitterTimeline tl = SearchServlet.search(backend, "", Order.CREATED_AT, "cache", 100, 0, SearchServlet.backend_hash, 60000);
                        checkContext(tl, false);
                    }
                    // if we still don't have any context, we are a bit helpless and hope that this situation
                    // will be better in the future. To prevent that this is called excessively fast, do a pause.
                    if (pendingContext.size() == 0) try {Thread.sleep(10000);} catch (InterruptedException e) {}
                }
            } catch (IOException e) {
            	DAO.severe(e.getMessage());
                try {Thread.sleep(10000);} catch (InterruptedException e1) {} // if the remote peer is down, throttle down
            }
        }

        if (pendingQueries.size() == 0) return -1;

        // take one of the pending queries or pending context and load the tweets
        String q = "";
        try {
            q = pendingQueries.iterator().next();
            pendingQueries.remove(q);
            pendingContext.remove(q);
            harvestedContext.add(q);
            TwitterTimeline tl = TwitterScraper.search(q, Order.CREATED_AT, true, false, 400);

            if (tl == null || tl.size() == 0) {
                // even if the result is empty, we must push this to the backend to make it possible that the query gets an update
                if (tl == null) tl = new TwitterTimeline(Order.CREATED_AT);
                tl.setQuery(q);
                PushThread pushThread = new PushThread(backend, tl);
                DAO.log( "starting push to backend; pendingQueries = " + pendingQueries.size() + ", pendingContext = " +
                        pendingContext.size() + ", harvestedContext = " + harvestedContext.size());
                executor.execute(pushThread);
                return -1;
            }

            // find content query strings and store them in the context cache
            checkContext(tl, true);

            // if we loaded a pending query, push results to backpeer right now
            tl.setQuery(q);
            PushThread pushThread = new PushThread(backend, tl);
            DAO.log( "starting push to backend; pendingQueries = " + pendingQueries.size() + ", pendingContext = " +
                    pendingContext.size() + ", harvestedContext = " + harvestedContext.size());
            executor.execute(pushThread);
            return tl.size();
        } catch (NoSuchElementException e) {
            // this is a concurrency glitch. just do nothing.
            return -1;
        }
    }

    public void stop() {
        executor.shutdown();
    }
}
