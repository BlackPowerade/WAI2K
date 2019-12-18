/*
 * GPLv3 License
 *
 *  Copyright (c) WAI2K by waicool20
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.waicool20.wai2k.script.modules


import com.waicool20.cvauto.android.AndroidRegion
import com.waicool20.cvauto.core.template.FileTemplate
import com.waicool20.wai2k.config.Wai2KConfig
import com.waicool20.wai2k.config.Wai2KProfile
import com.waicool20.wai2k.game.Echelon
import com.waicool20.wai2k.game.LocationId
import com.waicool20.wai2k.game.LogisticsSupport
import com.waicool20.wai2k.script.Navigator
import com.waicool20.wai2k.script.ScriptRunner
import com.waicool20.wai2k.util.Ocr
import com.waicool20.wai2k.util.doOCRAndTrim
import com.waicool20.wai2k.util.formatted
import com.waicool20.waicoolutils.logging.loggerFor
import com.waicool20.waicoolutils.mapAsync
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import java.time.Instant

class LogisticsSupportModule(
        scriptRunner: ScriptRunner,
        region: AndroidRegion,
        config: Wai2KConfig,
        profile: Wai2KProfile,
        navigator: Navigator
) : ScriptModule(scriptRunner, region, config, profile, navigator) {
    private val logger = loggerFor<LogisticsSupportModule>()
    override suspend fun execute() {
        if (!profile.logistics.enabled) return
        checkAndDispatchEchelons()
        checkLogisticTimers()
    }

    /**
     * Checks if any echelons require dispatching then dispatches them
     */
    private suspend fun checkAndDispatchEchelons() {
        val queue = profile.logistics.assignments
                // Map to echelon
                .mapKeys { gameState.echelons[it.key - 1] }
                // Keep all echelons that don't have an assignment already
                .filter { it.key.logisticsSupportAssignment == null }
                // Remove all echelons with disabled logistics
                .filter { it.key.logisticsSupportEnabled }
                // Keep all echelons that have configured assignments
                .filter { it.value.isNotEmpty() }
                // Remove all the echelons that have repairs ongoing
                .filterNot { it.key.hasRepairs() }
        // Return if no echelons to dispatch or logistic support 4/4 was reached
        if (queue.isEmpty() || logisticSupportLimitReached()) return
        navigator.navigateTo(LocationId.LOGISTICS_SUPPORT)
        queue.entries.shuffled().forEach { (echelon, ls) ->
            if (logisticSupportLimitReached()) {
                logger.info("4 logistic support have been dispatched, ignoring further assignments")
                return
            }
            logger.info("Valid assignments for $echelon: ${ls.joinToString()}")
            val assignment = LogisticsSupport.list
                    .filter { ls.contains(it.number) }
                    // Remove any logistic support that are being run by other echelons atm
                    .filter { l -> gameState.echelons.none { it.logisticsSupportAssignment?.logisticSupport == l } }
                    // Choose a random logistic support
                    .shuffled().firstOrNull()
            if (assignment != null) dispatchEchelon(echelon, assignment)
            yield()
        }
    }


    /**
     * Tries to return to home and receive echelons if any logistic timers have expired
     */
    private suspend fun checkLogisticTimers() {
        if (gameState.echelons.any { it.logisticsSupportAssignment?.eta?.isBefore(Instant.now()) == true }) {
            logger.info("An echelon probably came back, gonna check home")
            navigator.navigateTo(LocationId.HOME)
            navigator.checkLogistics()
        }
    }

    /**
     * Dispatches an echelon to some logistic support
     *
     * @param echelon Echelon to dispatch
     * @param nextMission Next Logistic Support mission to dispatch the echelon to
     */
    private suspend fun dispatchEchelon(echelon: Echelon, nextMission: LogisticsSupport) {
        logger.info("Next mission for $echelon is ${nextMission.number}")
        clickLogisticSupportChapter(nextMission)
        val missionIndex = (nextMission.number - 1) % 4

        if (missionRunning(missionIndex)) {
            logger.info("An echelon is already running that mission, maybe ocr failed when updating game state")
            gameState.requiresUpdate = true
            return
        }

        // Start mission
        clickMissionStart(missionIndex)

        delay(250)

        // Click the ok button of the popup if any of the resources broke the hard cap
        // Use subregion so it doesnt click dispatch ok instead
        region.subRegion(500, 90, 1155, 815).findBest(FileTemplate("ok.png"))
                ?.also { logger.info("One of the resources reached its limit!") }
                ?.region?.click()

        region.waitHas(FileTemplate("logistics/formation.png"), 10000)
        clickEchelon(echelon)
        // Click ok button
        delay(300)

        region.clickTemplateWhile(FileTemplate("ok.png")) { has(it) }

        // Wait for logistics mission icon to appear again
        region.subRegion(131, 306, 257, 118)
                .waitHas(FileTemplate("logistics/logistics.png"), 7000)

        // Check if mission is running
        if (missionRunning(missionIndex)) {
            // Update eta
            val eta = Instant.now() + nextMission.duration
            echelon.logisticsSupportAssignment = LogisticsSupport.Assignment(nextMission, eta)
            logger.info("Dispatched $echelon to logistic support ${nextMission.formattedString}, ETA: ${eta.formatted()}")
            scriptStats.logisticsSupportSent++
            return
        }
        // Mission not running, requirements not met
        logger.info("Logistic support failed to dispatch, are requirements being met?")
        logger.info("Disabled logistics support for echelon ${echelon.number}")
        delay(300)
        // Click close button
        region.subRegion(940, 757, 280, 107).click()
        // Disable echelon
        echelon.logisticsSupportEnabled = false
    }

    /**
     * Clicks the chapter of the given logistic support
     *
     * @param ls Logistic support
     */
    private suspend fun clickLogisticSupportChapter(ls: LogisticsSupport) {
        logger.info("Choosing logistics support chapter ${ls.chapter}")
        clickChapter(ls.chapter)
        logger.info("At logistics support chapter ${ls.chapter}")
    }

    /**
     * Checks if the mission for given index is running
     *
     * @param mission Index of mission from left to right 0-3
     */
    private fun missionRunning(mission: Int): Boolean {
        val missionRegion = region.subRegion(704 + (333 * mission), 219, 306, 856)
        return missionRegion.has(FileTemplate("logistics/retreat.png"))
    }

    /**
     * Clicks on mission start button for given mission index
     *
     * @param mission Index of mission from left to right 0-3
     */
    private suspend fun clickMissionStart(mission: Int) {
        logger.debug("Opening up the logistic support menu")
        // Left most mission button x: 704 y: 219 w: 306 h: 856
        val missionRegion = region.subRegion(704 + (333 * mission), 219, 306, 856)
        // Need a separate check region because the ammo icon might not be covered by the resource limit popup
        val checkRegion = region.subRegion(704 + (333 * 1), 219, 306, 856)
        missionRegion.clickWhile { checkRegion.has(FileTemplate("logistics/ammo.png")) }
    }

    /**
     * Clicks on the echelon when in the dispatch screen
     *
     * @param echelon Echelon to click
     */
    private suspend fun clickEchelon(echelon: Echelon) {
        logger.debug("Clicking the echelon")
        val eRegion = region.subRegion(162, 40, 170, region.height - 140)

        while (isActive) {
            delay(100)
            val echelons = eRegion.findBest(FileTemplate("echelons/echelon.png"), 8)
                    .map { it.region }
                    .map { it.copyAs<AndroidRegion>(it.x + 93, it.y - 40, 45, 100) }
                    .mapAsync { Ocr.forConfig(config, true).doOCRAndTrim(it).toInt() to it }
                    .toMap()
            logger.debug("Visible echelons: ${echelons.keys}")
            if (echelon.number in echelons.keys) {
                echelons[echelon.number]?.click()
                break
            }
            if (echelons.keys.isEmpty()) continue
            val lEchelon = echelons.keys.min() ?: echelons.keys.firstOrNull() ?: continue
            val hEchelon = echelons.keys.max() ?: echelons.keys.lastOrNull() ?: continue
            val lEchelonRegion = echelons[lEchelon] ?: continue
            val hEchelonRegion = echelons[hEchelon] ?: continue
            when {
                echelon.number <= lEchelon -> {
                    logger.debug("Swiping down the echelons")
                    lEchelonRegion.swipeTo(hEchelonRegion)
                }
                echelon.number >= hEchelon -> {
                    logger.debug("Swiping up the echelons")
                    hEchelonRegion.swipeTo(lEchelonRegion)
                }
            }
            delay(300)
        }
    }

    /**
     * Checks if the amount of ongoing logistic support is 4
     */
    private fun logisticSupportLimitReached(): Boolean {
        return gameState.echelons.mapNotNull { it.logisticsSupportAssignment }.size >= 4
    }
}
