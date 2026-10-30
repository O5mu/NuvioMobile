package com.nuvio.app.features.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong

internal fun Modifier.playerSurfaceTapGestures(
    layoutSize: IntSize,
    playbackGesturesEnabled: Boolean,
    playerControlsLockedState: State<Boolean>,
    onSurfaceTap: State<(Offset) -> Unit>,
    onSurfaceDoubleTap: State<(Offset) -> Unit>,
    activateHoldToSpeedState: State<() -> Unit>,
    deactivateHoldToSpeedState: State<() -> Unit>,
    revealLockedOverlayState: State<() -> Unit>,
): Modifier =
    pointerInput(layoutSize, playbackGesturesEnabled) {
        detectTapGestures(
            onPress = {
                tryAwaitRelease()
                deactivateHoldToSpeedState.value()
            },
            onTap = { offset -> onSurfaceTap.value(offset) },
            onDoubleTap = if (playbackGesturesEnabled) {
                { offset -> onSurfaceDoubleTap.value(offset) }
            } else null,
            onLongPress = if (playbackGesturesEnabled) {
                {
                    if (playerControlsLockedState.value) {
                        revealLockedOverlayState.value()
                    } else {
                        activateHoldToSpeedState.value()
                    }
                }
            } else null,
        )
    }

internal fun Modifier.playerSurfaceDragGestures(
    gestureController: PlayerGestureController?,
    layoutSize: IntSize,
    playbackGesturesEnabled: Boolean,
    sideGestureSystemEdgeExclusionPx: Float,
    playerControlsLockedState: State<Boolean>,
    touchGesturesEnabledState: State<Boolean>,
    isHoldToSpeedGestureActiveState: State<Boolean>,
    currentPositionMsState: State<Long>,
    currentDurationMsState: State<Long>,
    deactivateHoldToSpeedState: State<() -> Unit>,
    showHorizontalSeekPreviewState: State<(Long, Long) -> Unit>,
    showBrightnessFeedbackState: State<(Float) -> Unit>,
    showVolumeFeedbackState: State<(PlayerAudioLevel) -> Unit>,
    clearLiveGestureFeedbackState: State<() -> Unit>,
    revealLockedOverlayState: State<() -> Unit>,
    commitHorizontalSeekState: State<(Long) -> Unit>,
): Modifier =
    pointerInput(gestureController, layoutSize, sideGestureSystemEdgeExclusionPx, playbackGesturesEnabled) {
        if (!playbackGesturesEnabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown()
            if (playerControlsLockedState.value) {
                return@awaitEachGesture
            }
            if (!touchGesturesEnabledState.value) {
                return@awaitEachGesture
            }
            val controller = gestureController
            val width = size.width.toFloat().takeIf { it > 0f } ?: return@awaitEachGesture
            val height = size.height.toFloat().takeIf { it > 0f } ?: return@awaitEachGesture
            val sideGestureEdgeExclusionPx = sideGestureSystemEdgeExclusionPx
                .coerceAtMost(height * 0.25f)
            val isInSideGestureSystemEdge =
                down.position.y <= sideGestureEdgeExclusionPx ||
                    down.position.y >= height - sideGestureEdgeExclusionPx
            val region = when {
                isInSideGestureSystemEdge -> null
                down.position.x < width * PlayerLeftGestureBoundary -> PlayerSideGesture.Brightness
                down.position.x > width * PlayerRightGestureBoundary -> PlayerSideGesture.Volume
                else -> null
            }

            val initialBrightness = if (region == PlayerSideGesture.Brightness) {
                controller?.currentBrightness()
            } else {
                null
            }
            val initialVolume = if (region == PlayerSideGesture.Volume) {
                controller?.currentVolume()
            } else {
                null
            }

            var totalDx = 0f
            var totalDy = 0f
            var gestureMode: PlayerGestureMode? = null
            var verticalGestureActivationDy = 0f
            val horizontalSeekBaselineMs = currentPositionMsState.value
            var horizontalSeekPreviewMs = horizontalSeekBaselineMs
            var multiTouch = false

            while (true) {
                val event = awaitPointerEvent()
                // A second finger makes this a pinch, so any seek in progress is dropped.
                if (event.changes.count { it.pressed } > 1) {
                    multiTouch = true
                    break
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break

                val delta = change.position - change.previousPosition
                totalDx += delta.x
                totalDy += delta.y

                if (gestureMode == null) {
                    val holdToSpeedActive = isHoldToSpeedGestureActiveState.value
                    val verticalGestureActivationSlop = maxOf(
                        viewConfiguration.touchSlop * PlayerVerticalGestureTouchSlopMultiplier,
                        height * PlayerVerticalGestureMinHeightFraction,
                    )
                    val horizontalDominant =
                        !holdToSpeedActive &&
                            abs(totalDx) > viewConfiguration.touchSlop &&
                            abs(totalDx) > abs(totalDy)
                    val verticalDominant =
                        !holdToSpeedActive &&
                            abs(totalDy) > verticalGestureActivationSlop &&
                            abs(totalDy) > abs(totalDx) * PlayerVerticalGestureDominanceRatio

                    gestureMode = when {
                        horizontalDominant -> {
                            deactivateHoldToSpeedState.value()
                            PlayerGestureMode.HorizontalSeek
                        }

                        verticalDominant && region == PlayerSideGesture.Brightness && initialBrightness != null -> {
                            verticalGestureActivationDy = totalDy
                            PlayerGestureMode.Brightness
                        }

                        verticalDominant && region == PlayerSideGesture.Volume && initialVolume != null -> {
                            verticalGestureActivationDy = totalDy
                            PlayerGestureMode.Volume
                        }

                        else -> null
                    }

                    if (gestureMode == null) {
                        continue
                    }
                }

                when (gestureMode) {
                    PlayerGestureMode.HorizontalSeek -> {
                        val sensitivitySeconds = when {
                            currentDurationMsState.value >= 3_600_000L -> 120f
                            currentDurationMsState.value >= 1_800_000L -> 90f
                            else -> 60f
                        }
                        val previewOffsetMs =
                            ((totalDx / width) * sensitivitySeconds * 1000f).roundToLong()
                        val unclampedPreviewMs = horizontalSeekBaselineMs + previewOffsetMs
                        horizontalSeekPreviewMs = currentDurationMsState.value
                            .takeIf { it > 0L }
                            ?.let { durationMs ->
                                unclampedPreviewMs.coerceIn(0L, durationMs)
                            }
                            ?: unclampedPreviewMs.coerceAtLeast(0L)
                        showHorizontalSeekPreviewState.value(
                            horizontalSeekPreviewMs,
                            horizontalSeekBaselineMs,
                        )
                    }

                    PlayerGestureMode.Brightness -> {
                        val activeTotalDy = totalDy - verticalGestureActivationDy
                        val gestureDeltaFraction =
                            (-activeTotalDy / height) * PlayerVerticalGestureSensitivity
                        controller?.setBrightness((initialBrightness ?: 0f) + gestureDeltaFraction)
                            ?.let(showBrightnessFeedbackState.value)
                    }

                    PlayerGestureMode.Volume -> {
                        val activeTotalDy = totalDy - verticalGestureActivationDy
                        val gestureDeltaFraction =
                            (-activeTotalDy / height) * PlayerVerticalGestureSensitivity
                        controller?.setVolume((initialVolume?.fraction ?: 0f) + gestureDeltaFraction)
                            ?.let(showVolumeFeedbackState.value)
                    }
                }
                change.consume()
            }

            if (multiTouch) {
                clearLiveGestureFeedbackState.value()
            } else if (gestureMode == PlayerGestureMode.HorizontalSeek && !isHoldToSpeedGestureActiveState.value) {
                commitHorizontalSeekState.value(horizontalSeekPreviewMs)
                clearLiveGestureFeedbackState.value()
            }
        }
    }

/** Pinching this far apart (or the same ratio together) moves all the way between fit and fill. */
private const val PlayerPinchFullScale = 1.5f

/** How far a pinch must travel to switch stage; anything less springs back on release. */
private const val PlayerPinchCommitFraction = 0.25f

/**
 * Two fingers move the video between fit and fill. Runs in the initial pass and consumes
 * every change once a second finger lands, so taps, hold-to-speed and drags stand down.
 */
internal fun Modifier.playerSurfacePinchGestures(
    playbackGesturesEnabled: Boolean,
    playerControlsLockedState: State<Boolean>,
    touchGesturesEnabledState: State<Boolean>,
    isResizeFilledState: State<Boolean>,
    previewResizeFillState: State<(Float) -> Unit>,
    finishResizePinchState: State<(Boolean) -> Unit>,
): Modifier =
    pointerInput(playbackGesturesEnabled) {
        if (!playbackGesturesEnabled) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (playerControlsLockedState.value || !touchGesturesEnabledState.value) {
                return@awaitEachGesture
            }
            val startFraction = if (isResizeFilledState.value) 1f else 0f
            var fraction = startFraction
            var zoom = 1f
            var multiTouch = false
            var pinching = false
            var finished = false

            fun finish() {
                pinching = false
                finished = true
                val switched = abs(fraction - startFraction) >= PlayerPinchCommitFraction
                finishResizePinchState.value((startFraction > 0f) != switched)
            }

            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressedCount = event.changes.count { it.pressed }
                    if (pressedCount == 0) break
                    if (pressedCount >= 2) multiTouch = true
                    if (!multiTouch) continue
                    event.changes.forEach { it.consume() }

                    if (pressedCount < 2) {
                        if (pinching) finish()
                        continue
                    }
                    if (finished) continue

                    zoom *= event.calculateZoom()
                    if (!pinching) {
                        pinching = abs(1f - zoom) * event.calculateCentroidSize() > viewConfiguration.touchSlop
                    }
                    if (pinching) {
                        fraction = (startFraction + ln(zoom) / ln(PlayerPinchFullScale)).coerceIn(0f, 1f)
                        previewResizeFillState.value(fraction)
                    }
                }
            } finally {
                if (pinching) finish()
            }
        }
    }
