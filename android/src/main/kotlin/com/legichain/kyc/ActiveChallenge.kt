package com.legichain.kyc

import kotlin.math.abs

/** Pure state machine. Camera observations, not UI taps, complete actions. */
internal class ActiveChallenge(private val sequence: List<String>, private val started: Long) {
    data class Face(val yaw: Float, val pitch: Float, val smile: Float?, val leftEye: Float?, val rightEye: Float?) {
        fun neutral(): Boolean = abs(yaw) < 12 && abs(pitch) < 12 && (smile ?: 1f) < .45f &&
            (leftEye ?: 0f) > .65f && (rightEye ?: 0f) > .65f
    }
    data class Action(val action: String, val startedAtMs: Long, val endedAtMs: Long)
    enum class Phase { INSTRUCTION, READY, PERFORM, RETURN, COMPLETE, FAILED }
    var phase = Phase.INSTRUCTION; private set
    var index = 0; private set
    val action: String get() = sequence[index.coerceAtMost(sequence.lastIndex)]
    val records = mutableListOf<Action>()
    private var phaseSince = started
    private var neutralSince: Long? = null
    private var actionSince = 0L
    private var hitSince: Long? = null
    init { require(sequence.isNotEmpty() && sequence.size <= 5 && sequence.all { it in ACTIONS }) }

    fun update(now: Long, face: Face?): Phase {
        if (phase == Phase.COMPLETE || phase == Phase.FAILED) return phase
        if (now - started > 100_000) { phase = Phase.FAILED; return phase }
        if (phase == Phase.INSTRUCTION && now - phaseSince >= 1800) {
            phase = Phase.READY
            phaseSince = now
        }
        if (phase == Phase.READY) {
            if (face?.neutral() == true) {
                if (neutralSince == null) neutralSince = now
                if (now - neutralSince!! >= 500) {
                    phase = Phase.PERFORM
                    actionSince = now
                    hitSince = null
                }
            } else neutralSince = null
        } else if (phase == Phase.PERFORM || phase == Phase.RETURN) {
            if (now - actionSince > 7800) { phase = Phase.FAILED; return phase }
            if (face == null) { hitSince = null; neutralSince = null; return phase }
            if (phase == Phase.PERFORM) {
                val hit = when (action) {
                    "head_left" -> face.yaw < -22
                    "head_right" -> face.yaw > 22
                    "look_up" -> face.pitch > 18
                    "smile" -> (face.smile ?: 0f) > .75f
                    "blink" -> (face.leftEye ?: 1f) < .25f && (face.rightEye ?: 1f) < .25f
                    else -> false
                }
                if (hit) {
                    if (hitSince == null) hitSince = now
                    if (action == "blink" || now - hitSince!! >= 250) {
                        phase = Phase.RETURN
                        neutralSince = null
                    }
                } else hitSince = null
            } else if (face.neutral()) {
                if (neutralSince == null) neutralSince = now
                if (now - actionSince >= 1000 && now - neutralSince!! >= 250) {
                    records.add(Action(action, actionSince - started, now - started))
                    index++
                    phase = if (index == sequence.size) Phase.COMPLETE else Phase.INSTRUCTION
                    phaseSince = now
                    neutralSince = null
                }
            } else neutralSince = null
        }
        return phase
    }

    companion object { val ACTIONS = setOf("blink", "head_left", "head_right", "smile", "look_up") }
}
