/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wipro.ats.bdre.imcrawler.frontier;

import com.wipro.ats.bdre.imcrawler.crawler.Configurable;
import com.wipro.ats.bdre.imcrawler.crawler.CrawlConfig;
import com.wipro.ats.bdre.imcrawler.url.WebURL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author Yasser Ganjisaffar modified by AS294216
 */

public class Frontier extends Configurable {
    protected static final Logger logger = LoggerFactory.getLogger(Frontier.class);

    private static final String DATABASE_NAME = "PendingURLsDB";
    private static final int IN_PROCESS_RESCHEDULE_BATCH_SIZE = 100;
    protected WorkQueues workQueues;
    protected int pid;
    protected long instanceExecid;
    protected InProcessPagesDB inProcessPages;

    protected final Object mutex = new Object();
    protected final Object waitingList = new Object();

    protected boolean isFinished = false;

    protected long scheduledPages;

    protected Counters counters;

    public Frontier(CrawlConfig config, int pid, long instanceExecid) {
        super(config);
        this.counters = new Counters(config);
        this.pid = pid;
        this.instanceExecid = instanceExecid;
        try {
            workQueues = new WorkQueues(DATABASE_NAME, config.isResumableCrawling());
            if (config.isResumableCrawling()) {
                scheduledPages = counters.getValue(Counters.ReservedCounterNames.SCHEDULED_PAGES);
                inProcessPages = new InProcessPagesDB();
                long numPreviouslyInProcessPages = inProcessPages.getLength(inProcessPages.database);
                if (numPreviouslyInProcessPages > 0) {
                    logger.info("Rescheduling {} URLs from previous crawl.", numPreviouslyInProcessPages);
                    scheduledPages -= numPreviouslyInProcessPages;

                    List<WebURL> urls = inProcessPages.get(IN_PROCESS_RESCHEDULE_BATCH_SIZE, inProcessPages.database, pid);
                    while (!urls.isEmpty()) {
                        scheduleAll(urls);
                        inProcessPages.delete(urls.size(), inProcessPages.database);
                        urls = inProcessPages.get(IN_PROCESS_RESCHEDULE_BATCH_SIZE, inProcessPages.database, pid);
                    }
                }
            } else {
                inProcessPages = null;
                scheduledPages = 0;
            }
        } catch (Exception e) {
            logger.error("Error while initializing the Frontier", e);
            workQueues = null;
        }
    }

    public void scheduleAll(List<WebURL> urls) {
        int maxPagesToFetch = config.getMaxPagesToFetch();
        synchronized (mutex) {
            int newScheduledPage = 0;
            for (WebURL url : urls) {
                if ((maxPagesToFetch > 0) && ((scheduledPages + newScheduledPage) >= maxPagesToFetch)) {
                    break;
                }

                try {
                    workQueues.put(url, DATABASE_NAME, pid, instanceExecid);
                    newScheduledPage++;
                } catch (Exception e) {
                    logger.error("Error while putting the url in the work queue", e);
                }
            }
            if (newScheduledPage > 0) {
                scheduledPages += newScheduledPage;
                counters.increment(Counters.ReservedCounterNames.SCHEDULED_PAGES, newScheduledPage);
            }
            synchronized (waitingList) {
                waitingList.notifyAll();
            }
        }
    }

    public void schedule(WebURL url) {
        int maxPagesToFetch = config.getMaxPagesToFetch();
        synchronized (mutex) {
            try {
                if (maxPagesToFetch < 0 || scheduledPages < maxPagesToFetch) {
                    workQueues.put(url, DATABASE_NAME, pid, instanceExecid);
                    scheduledPages++;
                    counters.increment(Counters.ReservedCounterNames.SCHEDULED_PAGES);
                }
            } catch (Exception e) {
                logger.error("Error while putting the url in the work queue", e);
            }
        }
    }

    public void getNextURLs(int max, List<WebURL> result) {
        while (true) {
            synchronized (mutex) {
                if (isFinished) {
                    return;
                }
                try {
                    List<WebURL> curResults = workQueues.get(max, DATABASE_NAME, pid);
                    workQueues.delete(curResults.size(), DATABASE_NAME);
                    if (inProcessPages != null) {
                        for (WebURL curPage : curResults) {
                            inProcessPages.put(curPage, inProcessPages.database, pid, instanceExecid);
                        }
                    }
                    result.addAll(curResults);
                } catch (Exception e) {
                    logger.error("Error while getting next urls", e);
                }

                if (result.size() > 0) {
                    return;
                }
            }

            try {
                synchronized (waitingList) {
                    waitingList.wait();
                }
            } catch (InterruptedException ignored) {
                // Do nothing
            }
            if (isFinished) {
                return;
            }
        }
    }

    public void setProcessed(WebURL webURL) {
        counters.increment(Counters.ReservedCounterNames.PROCESSED_PAGES);
        if (inProcessPages != null) {
            if (!inProcessPages.removeURL(webURL, pid)) {
                logger.warn("Could not remove: {} from list of processed pages.", webURL.getURL());
            }
        }
    }

    public long getQueueLength() {
        return workQueues.getLength(DATABASE_NAME);
    }

    public long getNumberOfAssignedPages() {
        return inProcessPages.getLength(inProcessPages.database);
    }

    public long getNumberOfProcessedPages() {
        return counters.getValue(Counters.ReservedCounterNames.PROCESSED_PAGES);
    }

    public boolean isFinished() {
        return isFinished;
    }

    public void close() {
        workQueues.close();
        counters.close();
        if (inProcessPages != null) {
            inProcessPages.close();
        }
    }

    public void finish() {
        isFinished = true;
        synchronized (waitingList) {
            waitingList.notifyAll();
        }
    }
}