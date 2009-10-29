/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.impl;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Route;
import org.apache.camel.processor.Logger;
import org.apache.commons.logging.LogFactory;

/**
 * A throttle based {@link org.apache.camel.spi.RoutePolicy} which is capable of dynamic
 * throttling a route based on number of current inflight exchanges.
 *
 * @version $Revision$
 */
public class ThrottlingRoutePolicy extends RoutePolicySupport {

    // TODO: need to be JMX enabled as well

    public enum ThrottlingScope { Total, Route }

    private final Lock lock = new ReentrantLock();
    private ThrottlingScope scope = ThrottlingScope.Route;
    private int maxInflightExchanges = 1000;
    private int resumePercentOfMax = 70;
    private int resumeInflightExchanges = 700;
    private LoggingLevel loggingLevel = LoggingLevel.INFO;
    private Logger logger = new Logger(LogFactory.getLog(getClass()));

    public ThrottlingRoutePolicy() {
    }

    @Override
    public String toString() {
        return "ThrottlingRoutePolicyp[" + maxInflightExchanges + " / " + resumePercentOfMax + "%" + "]";
    }

    public void onExchangeDone(Route route, Exchange exchange) {
        // this works the best when this logic is executed when the exchange is done
        Consumer consumer = route.getConsumer();

        int size = getSize(consumer, exchange);
        if (maxInflightExchanges > 0 && size > maxInflightExchanges) {
            try {
                lock.lock();
                stopConsumer(size, consumer);
            } catch (Exception e) {
                handleException(e);
            } finally {
                lock.unlock();
            }
        }

        // reload size in case a race condition with too many at once being invoked
        // so we need to ensure that we read the most current side and start the consumer if we hit to low
        size = getSize(consumer, exchange);
        if (size <= resumeInflightExchanges) {
            try {
                lock.lock();
                startConsumer(size, consumer);
            } catch (Exception e) {
                handleException(e);
            } finally {
                lock.unlock();
            }
        }
    }

    public int getMaxInflightExchanges() {
        return maxInflightExchanges;
    }

    /**
     * Sets the upper limit of number of concurrent inflight exchanges at which point reached
     * the throttler should suspend the route.
     * <p/>
     * Is default 1000.
     *
     * @param maxInflightExchanges the upper limit of concurrent inflight exchanges
     */
    public void setMaxInflightExchanges(int maxInflightExchanges) {
        this.maxInflightExchanges = maxInflightExchanges;
        // recalculate, must be at least at 1
        this.resumeInflightExchanges = Math.max(resumePercentOfMax * maxInflightExchanges / 100, 1);
    }

    public int getResumePercentOfMax() {
        return resumePercentOfMax;
    }

    /**
     * Sets at which percentage of the max the throttler should start resuming the route.
     * <p/>
     * Will by default use 70%.
     *
     * @param resumePercentOfMax the percentage must be between 0 and 100
     */
    public void setResumePercentOfMax(int resumePercentOfMax) {
        if (resumePercentOfMax < 0 || resumePercentOfMax > 100) {
            throw new IllegalArgumentException("reconnectPercentOfMax must be a percentage between 0 and 100");
        }

        this.resumePercentOfMax = resumePercentOfMax;
        // recalculate, must be at least at 1
        this.resumeInflightExchanges = Math.max(resumePercentOfMax * maxInflightExchanges / 100, 1);
    }

    public ThrottlingScope getScope() {
        return scope;
    }

    /**
     * Sets which scope the throttling should be based upon, either route or total scoped.
     *
     * @param scope the scope
     */
    public void setScope(ThrottlingScope scope) {
        this.scope = scope;
    }

    public LoggingLevel getLoggingLevel() {
        return loggingLevel;
    }

    public Logger getLogger() {
        return logger;
    }

    /**
     * Sets the logger to use for logging throttling activity.
     *
     * @param logger the logger
     */
    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    /**
     * Sets the logging level to report the throttling activity.
     * <p/>
     * Is default <tt>INFO</tt> level.
     *
     * @param loggingLevel the logging level
     */
    public void setLoggingLevel(LoggingLevel loggingLevel) {
        this.loggingLevel = loggingLevel;
    }

    private int getSize(Consumer consumer, Exchange exchange) {
        if (scope == ThrottlingScope.Total) {
            return exchange.getContext().getInflightRepository().size();
        } else {
            Endpoint endpoint = consumer.getEndpoint();
            return exchange.getContext().getInflightRepository().size(endpoint);
        }
    }

    private void startConsumer(int size, Consumer consumer) throws Exception {
        boolean started = super.startConsumer(consumer);
        if (started) {
            logger.log("Throtteling consumer: " + size + " <= " + resumeInflightExchanges + " inflight exchange by resuming consumer.");
        }
    }

    private void stopConsumer(int size, Consumer consumer) throws Exception {
        boolean stopped = super.stopConsumer(consumer);
        if (stopped) {
            logger.log("Throtteling consumer: " + size + " > " + maxInflightExchanges + " inflight exchange by suspending consumer.");
        }
    }

}
