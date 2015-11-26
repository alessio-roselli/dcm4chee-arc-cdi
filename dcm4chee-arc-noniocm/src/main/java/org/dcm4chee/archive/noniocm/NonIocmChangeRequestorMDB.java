/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2013
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4chee.archive.noniocm;

import java.util.List;

import javax.ejb.ActivationConfigProperty;
import javax.ejb.EJB;
import javax.ejb.EJBException;
import javax.ejb.MessageDriven;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Inject;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;

import org.dcm4chee.archive.dto.ActiveService;
import org.dcm4chee.archive.entity.ActiveProcessing;
import org.dcm4chee.archive.processing.ActiveProcessingService;
import org.dcm4chee.archive.util.RetryBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Franz Willer <franz.willer@gmail.com>
 * 
 */

@MessageDriven(activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationType",
                                  propertyValue = "javax.jms.Queue"),
        @ActivationConfigProperty(propertyName = "destination",
                                  propertyValue = "queue/noneiocm"),
        @ActivationConfigProperty(propertyName = "acknowledgeMode",
                                  propertyValue = "Auto-acknowledge") })
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class NonIocmChangeRequestorMDB implements MessageListener {
    private static final Logger LOG = LoggerFactory.getLogger(NonIocmChangeRequestorMDB.class);
    
    public static final String UPDATED_STUDY_UID_MSG_PROPERTY = "studyIUID";
    
    private static final String JMS_DELIVERY_COUNT_MSG_PROPERTY = "JMSXDeliveryCount";

    @EJB
    private ActiveProcessingService activeProcessingService;
    
    @Inject
    private NonIOCMChangeRequestorService changeRequestorService;

    @Override
    public void onMessage(Message msg) {
        String processStudyIUID = getProcessStudyUID(msg);
        if(processStudyIUID == null) {
            return;
        }
        
        LOG.debug("Non-IOCM active-processing message for study {}", processStudyIUID);
        
        List<ActiveProcessing> nonIocmProcessings = getNonIOCMActiveProcessings(processStudyIUID);
        if(nonIocmProcessings == null) {
            return;
        }
        
        try {
            changeRequestorService.processNonIOCMRequest(processStudyIUID, nonIocmProcessings);
            activeProcessingService.deleteActiveProcesses(nonIocmProcessings);
        } catch (Throwable th) {
            LOG.warn("Failed to process Non-IOCM active-processing message", th);
         
            boolean retryable = RetryBean.isRetryable(th);
            int msgDeliveryCount = getMessageDeliveryCount(msg);
            //TODO: remove hard-coded number of retries
            // throw exception to force JMS retry
            if (retryable && msgDeliveryCount <= 3) {
                throw new EJBException("Failed to process Non-IOCM active-processing message");
            // no retry -> clean-up
            } else {
                activeProcessingService.deleteActiveProcesses(nonIocmProcessings);
            }
        } 
        
    }
    
    private static String getProcessStudyUID(Message msg) {
        try {
            return msg.getStringProperty(UPDATED_STUDY_UID_MSG_PROPERTY);
        } catch (JMSException e) {
            LOG.error("Received invalid Non-IOCM active-processing message", e);
            return null;
        }
    }
    
    private List<ActiveProcessing> getNonIOCMActiveProcessings(String studyUID) {
        try {
            return activeProcessingService.getActiveProcessesByStudy(studyUID, ActiveService.NON_IOCM_UPDATE);
        } catch (Exception e) {
            LOG.error("Could not fetch Non-IOCM active processings from database", e);
            return null;
        }
    }
    
    private static int getMessageDeliveryCount(Message msg) {
        try {
            return msg.getIntProperty(JMS_DELIVERY_COUNT_MSG_PROPERTY);
        } catch (JMSException e) {
            return Integer.MAX_VALUE;
        }
    }

}