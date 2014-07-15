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
 * Portions created by the Initial Developer are Copyright (C) 2011-2014
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

package org.dcm4chee.archive.patient;

import org.dcm4che3.data.Attributes;
import org.dcm4chee.archive.conf.StoreParam;
import org.dcm4chee.archive.entity.Patient;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 *
 */
public interface PatientService {

    /**
     * Query for existing Patient records with matching Patient ID's and/or
     * Patient demographics. If no or multiple existing Patient records matches,
     * a new Patient record is inserted. Otherwise the matching Patient record
     * - or if merged, following the merge path to the final dominate
     * Patient - will be updated. Called on receive of MPPS N-CREATE requests.
     * 
     * @param attrs Data Set of received MPPS N-CREATE Request
     * @param selector used PatientSelector
     * @param storeParam used StoreParam
     * @return updated or created Patient
     * @throws PatientCircularMergedException if a circular merge path is detected
     */
    Patient updateOrCreatePatientOnMPPSNCreate(Attributes attrs,
            PatientSelector selector, StoreParam storeParam)
            throws PatientCircularMergedException;

    /**
     * Query for existing Patient records with matching Patient ID's and/or
     * Patient demographics. If no or multiple existing Patient records matches,
     * a new Patient record is inserted. Otherwise the matching Patient record
     * - or if merged, following the merge path to the final dominate
     * Patient - will be updated. Called on receiving of the first Composite
     * Object of a Study.
     * 
     * @param attrs Data Set of received composite object
     * @param selector used PatientSelector
     * @param storeParam used StoreParam
     * @return updated or created Patient
     * @throws PatientCircularMergedException if a circular merge path is detected
     */
    Patient updateOrCreatePatientOnCStore(Attributes attrs,
            PatientSelector selector, StoreParam storeParam)
            throws PatientCircularMergedException;

    /**
     * Update existing Patient with attributes of received Composite Object.
     * Called on receiving additional Composite Objects to an already existing
     * Study.
     * 
     * @param patient
     * @param attrs
     * @param storeParam
     */
    void updatePatientByCStore(Patient patient, Attributes attrs,
            StoreParam storeParam);

    Patient updateOrCreatePatientByHL7(Attributes attrs, StoreParam storeParam)
            throws NonUniquePatientException, PatientMergedException;

    void mergePatientByHL7(Attributes attrs, Attributes mrg, StoreParam storeParam)
            throws NonUniquePatientException, PatientMergedException,
            PatientCircularMergedException;

    void linkPatient(Attributes attrs, Attributes otherAttrs,
            StoreParam storeParam) throws NonUniquePatientException,
            PatientMergedException;

    void unlinkPatient(Attributes attrs, Attributes otherAttrs)
            throws NonUniquePatientException, PatientMergedException;

}
