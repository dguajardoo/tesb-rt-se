/*
 * #%L
 * Service Activity Monitoring :: Agent
 * %%
 * Copyright (C) 2011 Talend Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.talend.esb.sam.agent.flowidprocessor;

import java.lang.ref.WeakReference;
import java.util.ListIterator;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.interceptor.Interceptor;
import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageUtils;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.phase.PhaseInterceptorChain;
import org.apache.cxf.ws.addressing.ContextUtils;
import org.talend.esb.sam.agent.eventproducer.EventProducerInterceptor;
import org.talend.esb.sam.agent.message.FlowIdHelper;

/**
 * The Class FlowIdProducerOut used for writing FlowId in outcoming messages.
 *
 * @param <T> the generic type
 */
public class FlowIdProducerOut<T extends Message> extends AbstractPhaseInterceptor<T> {

    private static final Logger LOG = Logger.getLogger(FlowIdProducerOut.class.getName());

    /**
     * Instantiates a new flow id producer out.
     */
    public FlowIdProducerOut() {
        super(Phase.USER_LOGICAL);
    }

    /* (non-Javadoc)
     * @see org.apache.cxf.interceptor.Interceptor#handleMessage(org.apache.cxf.message.Message)
     */
    public void handleMessage(T message) throws Fault {
        if (LOG.isLoggable(Level.FINEST)) {
            LOG.finest("FlowIdProducerOut Interceptor called. isOutbound: "
                    + MessageUtils.isOutbound(message) + ", isRequestor: "
                    + MessageUtils.isRequestor(message));
        }

        if (MessageUtils.isRequestor(message)) {
            handleRequestOut(message);
        } else {
            handleResponseOut(message);
        }

        // write FlowId to HTTP and Soap layer
        String flowId = FlowIdHelper.getFlowId(message);
        FlowIdProtocolHeaderCodec.writeFlowId(message, flowId);
        FlowIdSoapCodec.writeFlowId(message, flowId);

    }

    /**
     * Handling out responce.
     *
     * @param message the message
     * @throws Fault the fault
     */
    protected void handleResponseOut(T message) throws Fault {
        Message reqMsg = message.getExchange().getInMessage();
        if (reqMsg == null) {
            LOG.warning("InMessage is null!");
            return;
        }

        //No flowId for oneway message
        Exchange ex = reqMsg.getExchange();
        if (ex.isOneWay()) {
            return;
        }

        String reqFid = FlowIdHelper.getFlowId(reqMsg);

        //if some interceptor throws fault before FlowIdProducerIn fired 
        if (reqFid == null) {
            LOG.fine("Some interceptor throws fault.Setting FlowId in response.");
            reqFid = FlowIdProtocolHeaderCodec.readFlowId(message);
        }
        
        if (reqFid == null) {
             Message inMsg = ex.getInMessage();
             
             reqFid = FlowIdProtocolHeaderCodec.readFlowId(inMsg);
             if (null != reqFid){
             LOG.fine("FlowId '" + reqFid + "' found in message of fault incoming exchange.");
            
             LOG.fine("Calling EventProducerInterceptor to log IN message");
             EventProducerInterceptor epi = null;
             FlowIdHelper.setFlowId(inMsg, reqFid);
            
             ListIterator<Interceptor<? extends Message>> interceptors = 
            	inMsg.getInterceptorChain().getIterator();
            
             while (interceptors.hasNext() && epi == null) {
                Interceptor<? extends Message> interceptor = interceptors.next();
                
                if (interceptor instanceof EventProducerInterceptor) {
                    epi = (EventProducerInterceptor) interceptor;
                    epi.handleMessage(inMsg);
                }
             }
            }
        }

        if (reqFid == null) {
            reqFid = FlowIdSoapCodec.readFlowId(message);
        }
        
        if (reqFid != null) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("FlowId '" + reqFid + "' found in incoming message.");
            }
        } else {
            reqFid = ContextUtils.generateUUID();
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("No flowId found in incoming message! Generate new flowId " + reqFid);
            }
        }

        FlowIdHelper.setFlowId(message, reqFid);

    }

    /**
     * Handling out request.
     *
     * @param message the message
     * @throws Fault the fault
     */
    protected void handleRequestOut(T message) throws Fault {
        String flowId = FlowIdHelper.getFlowId(message);
        if (flowId == null
                && message.containsKey(PhaseInterceptorChain.PREVIOUS_MESSAGE)) {
            // Web Service consumer is acting as an intermediary
            @SuppressWarnings("unchecked")
            WeakReference<Message> wrPreviousMessage = (WeakReference<Message>) message
                    .get(PhaseInterceptorChain.PREVIOUS_MESSAGE);
            Message previousMessage = (Message) wrPreviousMessage.get();
            flowId = FlowIdHelper.getFlowId(previousMessage);
            if (flowId != null && LOG.isLoggable(Level.FINE)) {
                LOG.fine("flowId '" + flowId + "' found in previous message");
            }
        }

        if (flowId == null) {
            // No flowId found. Generate one.
            flowId = ContextUtils.generateUUID();
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("Generate new flowId '" + flowId + "'");
            }
        }

        FlowIdHelper.setFlowId(message, flowId);
    }

}
