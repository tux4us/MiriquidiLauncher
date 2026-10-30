// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.annotation.StringRes
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.camera.CameraMessage
import io.github.tux4us.miriquidilauncher.camera.ExtensionSetting
import io.github.tux4us.miriquidilauncher.camera.FlashSetting
import io.github.tux4us.miriquidilauncher.camera.FrameRateSetting
import io.github.tux4us.miriquidilauncher.camera.GridSetting
import io.github.tux4us.miriquidilauncher.camera.LensFacing
import io.github.tux4us.miriquidilauncher.camera.PhotoFormat
import io.github.tux4us.miriquidilauncher.camera.PriorityMode
import io.github.tux4us.miriquidilauncher.camera.RatioSetting
import io.github.tux4us.miriquidilauncher.camera.TimerSetting
import io.github.tux4us.miriquidilauncher.camera.VideoQualitySetting
import io.github.tux4us.miriquidilauncher.camera.WhiteBalanceSetting

/* Zuordnung der Kamera-Einstellungen zu Texten; Namen und Formate stehen in values/strings_camera_scope.xml. */

@StringRes
internal fun FlashSetting.labelRes(video: Boolean): Int = when (this) {
    FlashSetting.Off -> if (video) R.string.cam_torch_off else R.string.cam_flash_off
    FlashSetting.Auto -> R.string.cam_flash_auto
    FlashSetting.On -> if (video) R.string.cam_torch_on else R.string.cam_flash_on
}

@StringRes
internal fun TimerSetting.labelRes(): Int = when (this) {
    TimerSetting.Off -> R.string.cam_timer_off
    TimerSetting.S3 -> R.string.cam_timer_3
    TimerSetting.S10 -> R.string.cam_timer_10
}

@StringRes
internal fun RatioSetting.labelRes(): Int = when (this) {
    RatioSetting.R4x3 -> R.string.cam_ratio_4_3
    RatioSetting.R16x9 -> R.string.cam_ratio_16_9
}

@StringRes
internal fun GridSetting.labelRes(): Int = when (this) {
    GridSetting.Off -> R.string.cam_grid_off
    GridSetting.Thirds -> R.string.cam_grid_thirds
}

@StringRes
internal fun PhotoFormat.labelRes(): Int = when (this) {
    PhotoFormat.Jpeg -> R.string.cam_format_jpeg
    PhotoFormat.UltraHdr -> R.string.cam_format_uhdr
    PhotoFormat.Raw -> R.string.cam_format_raw
}

@StringRes
internal fun ExtensionSetting.labelRes(): Int = when (this) {
    ExtensionSetting.None -> R.string.cam_ext_none
    ExtensionSetting.Auto -> R.string.cam_ext_auto
    ExtensionSetting.Hdr -> R.string.cam_ext_hdr
    ExtensionSetting.Night -> R.string.cam_ext_night
    ExtensionSetting.Bokeh -> R.string.cam_ext_bokeh
    ExtensionSetting.FaceRetouch -> R.string.cam_ext_face
}

@StringRes
internal fun PriorityMode.labelRes(): Int = when (this) {
    PriorityMode.Speed -> R.string.cam_priority_speed
    PriorityMode.Quality -> R.string.cam_priority_quality
}

@StringRes
internal fun VideoQualitySetting.labelRes(): Int = when (this) {
    VideoQualitySetting.Highest -> R.string.cam_vq_highest
    VideoQualitySetting.Uhd -> R.string.cam_vq_uhd
    VideoQualitySetting.Fhd -> R.string.cam_vq_fhd
    VideoQualitySetting.Hd -> R.string.cam_vq_hd
    VideoQualitySetting.Sd -> R.string.cam_vq_sd
}

@StringRes
internal fun FrameRateSetting.labelRes(): Int = when (this) {
    FrameRateSetting.Auto -> R.string.cam_fps_auto
    FrameRateSetting.Fps30 -> R.string.cam_fps_30
    FrameRateSetting.Fps60 -> R.string.cam_fps_60
}

@StringRes
internal fun WhiteBalanceSetting.labelRes(): Int = when (this) {
    WhiteBalanceSetting.Auto -> R.string.cam_wb_auto
    WhiteBalanceSetting.Incandescent -> R.string.cam_wb_incandescent
    WhiteBalanceSetting.Fluorescent -> R.string.cam_wb_fluorescent
    WhiteBalanceSetting.Daylight -> R.string.cam_wb_daylight
    WhiteBalanceSetting.Cloudy -> R.string.cam_wb_cloudy
    WhiteBalanceSetting.Shade -> R.string.cam_wb_shade
}

@StringRes
internal fun LensFacing.labelRes(): Int = when (this) {
    LensFacing.Back -> R.string.cam_lens_back
    LensFacing.Front -> R.string.cam_lens_front
    LensFacing.External -> R.string.cam_lens_external
}

@StringRes
internal fun CameraMessage.labelRes(): Int = when (this) {
    CameraMessage.PhotoSaved -> R.string.cam_msg_photo_saved
    CameraMessage.VideoSaved -> R.string.cam_msg_video_saved
    CameraMessage.SaveFailed -> R.string.cam_msg_save_failed
    CameraMessage.BindFailed -> R.string.cam_msg_bind_failed
    CameraMessage.FormatFallback -> R.string.cam_msg_format_fallback
    CameraMessage.ExtensionFallback -> R.string.cam_msg_extension_fallback
    CameraMessage.NoAudio -> R.string.cam_msg_no_audio
    CameraMessage.NoViewer -> R.string.cam_msg_no_viewer
}

