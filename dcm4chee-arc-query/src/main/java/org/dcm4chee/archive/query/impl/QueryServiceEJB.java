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

package org.dcm4chee.archive.query.impl;

import com.mysema.commons.lang.CloseableIterator;
import com.mysema.query.BooleanBuilder;
import com.mysema.query.Tuple;
import com.mysema.query.types.Expression;
import com.mysema.query.types.Predicate;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.net.Device;
import org.dcm4chee.archive.conf.ArchiveDeviceExtension;
import org.dcm4chee.archive.conf.QueryParam;
import org.dcm4chee.archive.entity.*;
import org.dcm4chee.archive.query.DerivedSeriesFields;
import org.dcm4chee.archive.query.DerivedStudyFields;
import org.dcm4chee.archive.query.QueryContext;
import org.dcm4chee.archive.query.util.QueryBuilder;
import org.dcm4chee.mysema.query.jpa.hibernate.DetachedHibernateQueryFactory;
import org.hibernate.Session;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import java.util.Date;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 *
 */
@Stateless
public class QueryServiceEJB {

    static final Expression<?>[] PATIENT_STUDY_SERIES_ATTRS = {
        QStudy.study.pk,
        QSeriesQueryAttributes.seriesQueryAttributes.numberOfInstances,
        QSeriesQueryAttributes.seriesQueryAttributes.numberOfVisibleInstances,
        QSeriesQueryAttributes.seriesQueryAttributes.lastUpdateTime,
        QStudyQueryAttributes.studyQueryAttributes.numberOfInstances,
        QStudyQueryAttributes.studyQueryAttributes.numberOfSeries,
        QStudyQueryAttributes.studyQueryAttributes.modalitiesInStudy,
        QStudyQueryAttributes.studyQueryAttributes.sopClassesInStudy,
        QStudyQueryAttributes.studyQueryAttributes.numberOfVisibleInstances,
        QStudyQueryAttributes.studyQueryAttributes.lastUpdateTime,
        QueryBuilder.seriesAttributesBlob.encodedAttributes,
        QueryBuilder.studyAttributesBlob.encodedAttributes,
        QueryBuilder.patientAttributesBlob.encodedAttributes
    };

    @PersistenceContext(name = "dcm4chee-arc", unitName = "dcm4chee-arc")
    EntityManager em;

    @Inject
    private Device device;

    @Inject
    DetachedHibernateQueryFactory queryFactory;

    public Attributes getSeriesAttributes(Long seriesPk, QueryContext context) {
        String viewID = context.getQueryParam().getQueryRetrieveView().getViewID();
        Tuple result = queryFactory.query(em.unwrap(Session.class))
            .from(QSeries.series)
            .join(QSeries.series.attributesBlob, QueryBuilder.seriesAttributesBlob)
            .leftJoin(QSeries.series.queryAttributes, QSeriesQueryAttributes.seriesQueryAttributes)
                .on(QSeriesQueryAttributes.seriesQueryAttributes.viewID.eq(viewID))
            .join(QSeries.series.study, QStudy.study)
            .join(QStudy.study.attributesBlob, QueryBuilder.studyAttributesBlob)
            .leftJoin(QStudy.study.queryAttributes, QStudyQueryAttributes.studyQueryAttributes)
                .on(QStudyQueryAttributes.studyQueryAttributes.viewID.eq(viewID))
            .join(QStudy.study.patient, QPatient.patient)
            .join(QPatient.patient.attributesBlob, QueryBuilder.patientAttributesBlob)
            .where(QSeries.series.pk.eq(seriesPk))
            .singleResult(PATIENT_STUDY_SERIES_ATTRS);

        Integer numberOfSeriesRelatedInstances =
                result.get(QSeriesQueryAttributes.seriesQueryAttributes.numberOfInstances);
        Integer numberOfSeriesVisibleInstances;
        Date seriesLastUpdateTime;
        if (numberOfSeriesRelatedInstances == null) {
            SeriesQueryAttributes seriesQueryAttributes =
                    reCalculateSeriesQueryAttributes(seriesPk, context.getQueryParam());
            numberOfSeriesRelatedInstances = seriesQueryAttributes.getNumberOfInstances();
            numberOfSeriesVisibleInstances = seriesQueryAttributes.getNumberOfVisibleInstances();
            seriesLastUpdateTime = seriesQueryAttributes.getLastUpdateTime();
        } else {
            numberOfSeriesVisibleInstances = result.get(QSeriesQueryAttributes
                    .seriesQueryAttributes.numberOfVisibleInstances);
            seriesLastUpdateTime = result.get(QSeriesQueryAttributes
                    .seriesQueryAttributes.lastUpdateTime);
        }


        int numberOfStudyRelatedSeries;
        String modalitiesInStudy;
        String sopClassesInStudy;
        int numberOfStudyVisibleInstances;
        Date studyLastUpdateTime;
        Integer numberOfStudyRelatedInstances =
                result.get(QStudyQueryAttributes.studyQueryAttributes.numberOfInstances);
        if (numberOfStudyRelatedInstances == null) {
            StudyQueryAttributes studyQueryAttributes =
                    reCalculateStudyQueryAttributes(result.get(QStudy.study.pk), context.getQueryParam());
            numberOfStudyRelatedInstances = studyQueryAttributes.getNumberOfInstances();
            numberOfStudyRelatedSeries = studyQueryAttributes.getNumberOfSeries();
            modalitiesInStudy = studyQueryAttributes.getRawModalitiesInStudy();
            sopClassesInStudy = studyQueryAttributes.getRawSOPClassesInStudy();
            numberOfStudyVisibleInstances = studyQueryAttributes.getNumberOfVisibleInstances();
            studyLastUpdateTime = studyQueryAttributes.getLastUpdateTime();
        } else {
            numberOfStudyRelatedSeries =
                    result.get(QStudyQueryAttributes.studyQueryAttributes.numberOfSeries);
            modalitiesInStudy = 
                    result.get(QStudyQueryAttributes.studyQueryAttributes.modalitiesInStudy);
            sopClassesInStudy = 
                    result.get(QStudyQueryAttributes.studyQueryAttributes.sopClassesInStudy);
            numberOfStudyVisibleInstances = result.get(QStudyQueryAttributes.studyQueryAttributes.numberOfVisibleInstances);
            studyLastUpdateTime = result.get(QStudyQueryAttributes.studyQueryAttributes.lastUpdateTime);
        }
        byte[] seriesBytes =
                result.get(QueryBuilder.seriesAttributesBlob.encodedAttributes);
        byte[] studyBytes =
                result.get(QueryBuilder.studyAttributesBlob.encodedAttributes);
        byte[] patientBytes =
                result.get(QueryBuilder.patientAttributesBlob.encodedAttributes);

        Attributes patientAttrs = new Attributes();
        Attributes studyAttrs = new Attributes();
        Attributes seriesAttrs = new Attributes();
        Utils.decodeAttributes(patientAttrs, patientBytes);
        Utils.decodeAttributes(studyAttrs, studyBytes);
        Utils.decodeAttributes(seriesAttrs, seriesBytes);
        Attributes attrs = Utils.mergeAndNormalize(patientAttrs, studyAttrs, seriesAttrs);
        ArchiveDeviceExtension ade = context.getArchiveAEExtension()
                .getApplicationEntity().getDevice().getDeviceExtension
                        (ArchiveDeviceExtension.class);
        Utils.setStudyQueryAttributes(attrs,
                numberOfStudyRelatedSeries,
                numberOfStudyRelatedInstances,
                modalitiesInStudy,
                sopClassesInStudy,
                numberOfStudyVisibleInstances,
                ade.getPrivateDerivedFields().findStudyNumberOfVisibleInstancesTag(),
                studyLastUpdateTime,
                ade.getPrivateDerivedFields().findStudyUpdateTimeTag());
        Utils.setSeriesQueryAttributes(attrs,
                numberOfSeriesRelatedInstances,
                numberOfSeriesVisibleInstances,
                ade.getPrivateDerivedFields().findSeriesNumberOfVisibleInstancesTag(),
                seriesLastUpdateTime,
                ade.getPrivateDerivedFields().findSeriesUpdateTimeTag());
        return attrs;
    }

    Predicate createPredicate(Predicate initial, QueryParam queryParam) {
        BooleanBuilder builder = new BooleanBuilder(initial);
        builder.and(QueryBuilder.hideRejectedInstance(queryParam));
        builder.and(QueryBuilder.hideRejectionNote(queryParam));
        builder.and(QueryBuilder.hideDummyInstances());
        return builder;
    }

    /**
     * Creates or updates StudyQueryAttributes
     *
     * @param studyPk primary key of study
     * @param queryParam
     * @return updated or created StudyQueryAttributes
     */
    public StudyQueryAttributes reCalculateStudyQueryAttributes(
            Long studyPk, QueryParam queryParam) {

        DerivedStudyFields studyDerivedFields = new DefaultDerivedStudyFields(device);

        Study study = em.getReference(Study.class, studyPk);
        try (
            CloseableIterator<Tuple> results = queryFactory.query(
                    em.unwrap(Session.class))
                .from(QInstance.instance)
                .innerJoin(QInstance.instance.series, QSeries.series)
                .where(createPredicate(
                        QSeries.series.study.pk.eq(studyPk), queryParam))
                .iterate(studyDerivedFields.fields())) {
            while (results.hasNext()) {
                studyDerivedFields.addInstance(results.next(), queryParam);
            }
        }

        StudyQueryAttributes queryAttrs;
        try {
            Object result = em.createNamedQuery(StudyQueryAttributes.FIND_BY_VIEW_ID_AND_STUDY_FK)
                    .setParameter(1, queryParam.getQueryRetrieveView().getViewID())
                    .setParameter(2, studyPk)
                    .getSingleResult();

            // update
            queryAttrs = (StudyQueryAttributes) result;
            populateStudyQueryAttributes(studyDerivedFields, queryAttrs);
            em.merge(queryAttrs);

        } catch (NoResultException nre) {
            // create new
            queryAttrs = new StudyQueryAttributes();
            queryAttrs.setViewID(queryParam.getQueryRetrieveView().getViewID());
            queryAttrs.setStudy(study);
            populateStudyQueryAttributes(studyDerivedFields, queryAttrs);
            em.persist(queryAttrs);
        }
        return queryAttrs;
    }

    private void populateStudyQueryAttributes(DerivedStudyFields studyDerivedFields, StudyQueryAttributes queryAttrs) {
        queryAttrs.setNumberOfInstances(studyDerivedFields.getNumberOfInstances());
        if (studyDerivedFields.getNumberOfInstances() > 0) {
            queryAttrs.setNumberOfSeries(studyDerivedFields.getSeriesPKs().size());
            queryAttrs.setModalitiesInStudy(studyDerivedFields.getMods().toArray
                    (new String[studyDerivedFields.getMods().size()]));
            queryAttrs.setSOPClassesInStudy(studyDerivedFields.getCuids()
                    .toArray(new String[studyDerivedFields.getCuids().size()]));
            queryAttrs.setRetrieveAETs(studyDerivedFields.getRetrieveAETs());
            queryAttrs.setAvailability(studyDerivedFields.getAvailability());
            queryAttrs.setLastUpdateTime(studyDerivedFields.getLastUpdateTime());
            queryAttrs.setNumberOfVisibleInstances(studyDerivedFields.getNumberOfVisibleImages());
            queryAttrs.setNumberOfVisibleSeries(studyDerivedFields.getNumberOfVisibleSeries());
        }
    }

    /**
     * Creates or updates SeriesQueryAttributes
     *
     * @param seriesPk primary key of series
     * @param queryParam
     * @return updated or created SeriesQueryAttributes
     */
    public SeriesQueryAttributes reCalculateSeriesQueryAttributes(
            Long seriesPk, QueryParam queryParam) {

        DerivedSeriesFields seriesDerivedFields = new DefaultDerivedSeriesFields(device);

        Series series = em.getReference(Series.class, seriesPk);
        try (
            CloseableIterator<Tuple> results = queryFactory.query(
                    em.unwrap(Session.class))
                .from(QInstance.instance)
                .where(createPredicate(QInstance.instance.series.pk.eq(seriesPk), queryParam))
                .iterate(seriesDerivedFields.fields())) {
            while (results.hasNext()) {
                seriesDerivedFields.addInstance(results.next(), queryParam);
            }
        }

        SeriesQueryAttributes queryAttrs;
        try {
            Object result = em.createNamedQuery(SeriesQueryAttributes.FIND_BY_VIEW_ID_AND_SERIES_FK)
                    .setParameter(1, queryParam.getQueryRetrieveView().getViewID())
                    .setParameter(2, seriesPk)
                    .getSingleResult();

            // update
            queryAttrs = ((SeriesQueryAttributes) result);
            populateSeriesDerivedFields(seriesDerivedFields, queryAttrs);
            em.merge(queryAttrs);

        } catch (NoResultException nre) {
            // create new
            queryAttrs = new SeriesQueryAttributes();
            queryAttrs.setSeries(series);
            queryAttrs.setViewID(queryParam.getQueryRetrieveView().getViewID());
            populateSeriesDerivedFields(seriesDerivedFields, queryAttrs);
            em.persist(queryAttrs);
        }
        return queryAttrs;
    }

    private void populateSeriesDerivedFields(DerivedSeriesFields seriesDerivedFields, SeriesQueryAttributes queryAttrs) {
        queryAttrs.setNumberOfInstances(seriesDerivedFields.getNumberOfInstances());
        if (seriesDerivedFields.getNumberOfInstances() > 0) {
            queryAttrs.setRetrieveAETs(seriesDerivedFields.getRetrieveAETs());
            queryAttrs.setAvailability(seriesDerivedFields.getAvailability());
            queryAttrs.setLastUpdateTime(seriesDerivedFields.getLastUpdateTime());
            queryAttrs.setNumberOfVisibleInstances(seriesDerivedFields.getNumberOfVisibleImages());
        }
    }

}
