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
 * Portions created by the Initial Developer are Copyright (C) 2011-2015
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

package org.dcm4chee.archive.hsm.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Code;
import org.dcm4che3.data.Tag;
import org.dcm4che3.net.Device;
import org.dcm4che3.util.AttributesFormat;
import org.dcm4chee.archive.code.CodeService;
import org.dcm4chee.archive.conf.ArchivingRule;
import org.dcm4chee.archive.entity.ArchivingTask;
import org.dcm4chee.archive.entity.Instance;
import org.dcm4chee.archive.entity.Location;
import org.dcm4chee.archive.entity.Utils;
import org.dcm4chee.archive.store.StoreContext;
import org.dcm4chee.storage.ContainerEntry;
import org.dcm4chee.storage.archiver.service.ArchiverContext;
import org.dcm4chee.storage.archiver.service.ArchiverService;
import org.dcm4chee.storage.conf.StorageDeviceExtension;
import org.dcm4chee.storage.conf.StorageSystemGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 *
 */
@Stateless
public class ArchivingSchedulerEJB {

    private static final Logger LOG =
            LoggerFactory.getLogger(ArchivingSchedulerEJB.class);

    private static final String INSTANCE_PK = "instance_pk";
    private static final String DIGEST = "digest";
    private static final String OTHER_ATTRS_DIGEST = "otherAttrsDigest";
    private static final String FILE_SIZE = "fileSize";
    private static final String TRANSFER_SYNTAX = "transferSyntax";
    private static final String TIME_ZONE = "timeZone";
    private static final String DELETE_SOURCE = "deleteSource";

    @PersistenceContext(unitName="dcm4chee-arc")
    private EntityManager em;

    @Inject
    private Device device;

    @Inject
    private CodeService codeService;

    @Inject
    private ArchiverService archiverService;


    public void onStoreInstance(StoreContext storeContext,
            ArchivingRule archivingRule) {
        Attributes attrs = storeContext.getAttributes();
        String seriesInstanceUID = attrs.getString(Tag.SeriesInstanceUID);
        Date archivingTime = new Date(System.currentTimeMillis()
                + archivingRule.getDelayAfterInstanceStored() * 1000L);
        ArchivingTask task;
        try {
            task = em.createNamedQuery(
                    ArchivingTask.FIND_BY_SERIES_INSTANCE_UID, ArchivingTask.class)
                    .setParameter(1, seriesInstanceUID)
                    .getSingleResult();
            task.setArchivingTime(archivingTime);
            LOG.debug("Updates {}", task);
        } catch (NoResultException nre) {
            task = new ArchivingTask();
            task.setSeriesInstanceUID(seriesInstanceUID);
            task.setArchivingTime(archivingTime);
            task.setSourceStorageSystemGroupID(storeContext.getFileRef().getStorageSystemGroupID());
            task.setTargetStorageSystemGroupIDs(archivingRule.getStorageSystemGroupIDs());
            task.setTargetName(getTargetName(attrs, archivingRule.getStorageSystemGroupIDs()));
            Code delayReasonCode = archivingRule.getDelayReasonCode();
            if (delayReasonCode != null)
                task.setDelayReasonCode(codeService.findOrCreate(delayReasonCode));
            em.persist(task);
            LOG.info("Create {}", task);
        }
    }

    public ArchivingTask scheduleNextArchivingTask() throws IOException {
        List<ArchivingTask> results = em.createNamedQuery(
                ArchivingTask.FIND_READY_TO_ARCHIVE, ArchivingTask.class)
            .setMaxResults(1)
            .getResultList();
        
        if (results.isEmpty())
            return null;

        ArchivingTask task = results.get(0);
        scheduleArchivingTask(task);
        em.remove(task);
        return task;
    }

    public void scheduleArchivingTask(ArchivingTask task) throws IOException {
        LOG.info("Scheduling {}", task);
        List<Instance> insts = em
                .createNamedQuery(Instance.FIND_BY_SERIES_INSTANCE_UID, Instance.class)
                .setParameter(1, task.getSeriesInstanceUID()).getResultList();
        scheduleInstances(insts, task.getSourceStorageSystemGroupID(), task.getTargetStorageSystemGroupIDs(), task.getTargetName(), false);
        LOG.info("Scheduled {}", task);
    }
    
    public void scheduleStudy(String studyIUID, String sourceGroupID, String targetGroupID, boolean deleteSource) throws IOException {
        LOG.info("Scheduling archiving study={}, sourceStorageGroupID={}, targetStorageGroupID={}, deleteSource={}", 
        		studyIUID, sourceGroupID, targetGroupID, deleteSource);
        List<String> seriesUIDs = em.createQuery("SELECT se.seriesInstanceUID FROM Series se JOIN FETCH se.study st WHERE st.studyInstanceUID = ?1",
                        String.class)
                .setParameter(1, studyIUID)
                .getResultList();
        for (String uid : seriesUIDs) {
        	scheduleSeries(uid, sourceGroupID, targetGroupID, deleteSource);
        }
        LOG.info("Scheduled archiving study={}, sourceStorageGroupID={}, targetStorageGroupID={}, deleteSource={}", 
        		new Object[] {studyIUID, sourceGroupID, targetGroupID, deleteSource});
    	
    }
    
    public void scheduleSeries(String seriesIUID, String sourceGroupID, String targetGroupID, boolean deleteSource) throws IOException {
        LOG.info("Scheduling archiving series={}, sourceStorageGroupID={}, targetStorageGroupID={}, deleteSource={}", 
        		new Object[] {seriesIUID, sourceGroupID, targetGroupID, deleteSource});
        List<Instance> insts = em
                .createNamedQuery(Instance.FIND_BY_SERIES_INSTANCE_UID,
                        Instance.class)
                .setParameter(1, seriesIUID)
                .getResultList();
        if (insts.size() > 0) {
	        scheduleInstances(insts, sourceGroupID, new String[]{targetGroupID}, getTargetName(insts.get(0), targetGroupID), deleteSource);
	        LOG.info("Scheduled archiving {} instances of series={}, sourceStorageGroupID={}, targetStorageGroupID={}, deleteSource={}", 
	        		new Object[] {insts.size(), seriesIUID, sourceGroupID, targetGroupID, deleteSource});
        }    	
    }
    
	public void scheduleInstances(List<Instance> insts, String sourceGroupID, String[] targetStorageSystemGroupIDs, String targetName, boolean deleteSource) throws IOException {
        @SuppressWarnings("unchecked")
		ArrayList<ContainerEntry>[] entriesPerTarget = new ArrayList[targetStorageSystemGroupIDs.length];
        for (int i = 0 ; i < entriesPerTarget.length ; i++)
        	entriesPerTarget[i] = new ArrayList<ContainerEntry>(insts.size());
        Location selected;
        String storageSystemGroupID;
        for (Instance inst : insts) {
        	selected = null;
        	boolean[] existsOnTarget = new boolean[entriesPerTarget.length];
            for (Location location : inst.getLocations()) {
                storageSystemGroupID = location.getStorageSystemGroupID();
            	if (storageSystemGroupID.equals(sourceGroupID)) {
                    selected = location;
            	} else {
	            	for (int i = 0 ; i < entriesPerTarget.length ; i++) {
	                    if (storageSystemGroupID.equals(targetStorageSystemGroupIDs[i])) {
	                        LOG.info("{} already archived to Storage System Group {} - skip from archiving",
	                                inst, storageSystemGroupID);
	                        existsOnTarget[i] = true;
	                    } 
	                }
            	}
                if (selected == null) {
                    LOG.info("{} not available at Storage System Group {} - skip from archiving", inst, sourceGroupID);
                } else {
	                ContainerEntry entry = new ContainerEntry.Builder(inst.getSopInstanceUID(),
	                        location.getDigest())
	                    .setSourceStorageSystemGroupID(location.getStorageSystemGroupID())
	                    .setSourceStorageSystemID(location.getStorageSystemID())
	                    .setSourceName(location.getStorageSystemID())
	                    .setProperty(INSTANCE_PK, inst.getPk())
	                    .setProperty(DIGEST, location.getDigest())
	                    .setProperty(OTHER_ATTRS_DIGEST, location.getOtherAttsDigest())
	                    .setProperty(FILE_SIZE, location.getSize())
	                    .setProperty(TRANSFER_SYNTAX, location.getTransferSyntaxUID())
	                    .setProperty(TIME_ZONE, location.getTimeZone()).build();

	                for (int i = 0 ; i < entriesPerTarget.length ; i++) {
	                	if (!existsOnTarget[i])
	                		entriesPerTarget[i].add(entry);
	                }
	                
	                
                }
            }
        }
        for (int i = 0 ; i < entriesPerTarget.length ; i++) {
	        ArchiverContext ctx = archiverService.createContext(
	        		targetStorageSystemGroupIDs[i],
	                targetName);
	        ctx.setEntries(entriesPerTarget[i]);
	        ctx.setProperty(DELETE_SOURCE, new Boolean(deleteSource));
	        archiverService.scheduleStore(ctx);
        }
    }

    private StorageDeviceExtension storageDeviceExtension() {
        return device.getDeviceExtension(StorageDeviceExtension.class);
    }

	private String getTargetName(Attributes attrs, String... groupIDs ) {
		StorageSystemGroup grp = storageDeviceExtension().getStorageSystemGroup(groupIDs[0]);
		String pattern = grp.getStorageFilePathFormat();
		return AttributesFormat.valueOf(pattern).format(attrs);
	}
	
    private String getTargetName(Instance instance, String... groupIDs) {
		Attributes attrs = Utils.mergeAndNormalize(instance.getSeries().getStudy().getAttributes(),instance.getSeries().getAttributes(),instance.getAttributes());
		return getTargetName(attrs, groupIDs);
	}


    public void onContainerEntriesStored(ArchiverContext ctx) {
        List<ContainerEntry> entries = ctx.getEntries();
        for (ContainerEntry entry : entries) {
            Instance inst = em.find(Instance.class, entry.getProperty(INSTANCE_PK));
            Location location = new Location.Builder()
                .storageSystemGroupID(ctx.getStorageSystemGroupID())
                .storageSystemID(ctx.getStorageSystemID())
                .storagePath(ctx.getName())
                .entryName(entry.getName())
                .digest((String) entry.getProperty(DIGEST))
                .otherAttsDigest((String) entry.getProperty(OTHER_ATTRS_DIGEST))
                .size((Long) entry.getProperty(FILE_SIZE))
                .transferSyntaxUID((String) entry.getProperty(TRANSFER_SYNTAX))
                .timeZone((String) entry.getProperty(TIME_ZONE))
                .build();
            inst.getLocations().add(location);
            em.persist(location);
            LOG.info("Create {}", location);
        }
    }

}
