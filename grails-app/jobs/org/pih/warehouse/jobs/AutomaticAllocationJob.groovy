/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/
package org.pih.warehouse.jobs

import grails.util.Holders
import org.pih.warehouse.core.ActivityCode
import org.pih.warehouse.core.Location
import org.pih.warehouse.requisition.Requisition
import org.quartz.JobExecutionContext

class AutomaticAllocationJob {

    def allocationService
    def requisitionService
    def locationService

    def sessionRequired = false

    // Never run two allocation passes at once - allocation reads availability that a concurrent pass would be
    // mutating. Together with the settle delay below, this keeps auto-allocation effectively serialized so two
    // outbounds cannot allocate the same stock (OBLS-919).
    static concurrent = false

    static triggers = {
        cron name: JobUtils.getCronName(AutomaticAllocationJob),
        cronExpression: JobUtils.getCronExpression(AutomaticAllocationJob)
    }

    def execute(JobExecutionContext context) {
        if (!Holders.config.openboxes.jobs.automaticAllocationJob.enabled) {
            log.info"Automatic allocation job is disabled"
            return
        }

        Long settleDelay = settleDelayInMilliseconds

        String requisitionId = context.mergedJobDataMap.get('requisitionId')
        if (requisitionId) {
            allocationService.allocateRequisition(requisitionId)
            // Pause before this execution returns so the next serialized pass (concurrent=false) starts only
            // after this requisition's asynchronous availability refresh has settled - otherwise a follow-up
            // pass could evaluate stale availability and allocate stock this one already issued (OBLS-919).
            settle(settleDelay)
            return
        }

        List<Location> facilities =
                locationService.getLocationsSupportingActivities([ActivityCode.AUTOMATIC_ALLOCATION_ENABLED])
        log.info "Running automatic allocation job for all pending requisitions... "
        facilities.each { Location facility ->
            List<Requisition> pendingRequisitions = requisitionService.getRequisitionsPendingAutoAllocation(facility)
            pendingRequisitions.eachWithIndex { Requisition requisition, int index ->
                allocationService.allocateRequisition(requisition.id)
                // OBLS-919: product availability is refreshed asynchronously after each allocation/issuance, so
                // wait between requisitions in the same sweep to let that refresh settle. Without this, a second
                // requisition would be evaluated against stale availability and could allocate stock the first
                // one already issued (producing negative SoH). No wait needed after the last requisition.
                if (index < pendingRequisitions.size() - 1) {
                    settle(settleDelay)
                }
            }
        }
    }

    /**
     * Best-effort pause to let the asynchronous product-availability refresh (scheduled after each
     * allocation/issuance) complete before the next requisition is evaluated.
     */
    private static void settle(Long millis) {
        if (millis > 0) {
            sleep(millis)
        }
    }

    /**
     * Derive the settle window from the product-availability refresh delay plus a small buffer, mirroring the
     * heuristic used elsewhere (e.g. RequisitionEventService). Returns 0 when no refresh delay is configured.
     */
    private static Long getSettleDelayInMilliseconds() {
        def refreshDelay = Holders.config.openboxes.jobs.refreshProductAvailabilityJob.delayInMilliseconds
        return refreshDelay ? Long.valueOf(refreshDelay.toString()) + 1000L : 0L
    }
}
