package online.rushcounty.aligner.ar

import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo

/** Lens + active sensor area, read from Camera2 metadata. Sizes are in the sensor's native orientation. */
data class CameraIntrinsics(
    val focalMm: Double,
    val activeWidthMm: Double,
    val activeHeightMm: Double,
) {
    /** Aspect (long/short) of the active array, used to pick a matching preview stream. */
    val aspect: Double get() = maxOf(activeWidthMm, activeHeightMm) / minOf(activeWidthMm, activeHeightMm)

    companion object {
        @OptIn(ExperimentalCamera2Interop::class)
        fun from(info: CameraInfo): CameraIntrinsics? = runCatching {
            val c2 = Camera2CameraInfo.from(info)
            val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: return null
            val phys = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            val pixArray = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val active = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            // Physical size covers the full pixel array; scale down to the active (imaging) region.
            val fx = if (pixArray != null && active != null && pixArray.width > 0) active.width().toDouble() / pixArray.width else 1.0
            val fy = if (pixArray != null && active != null && pixArray.height > 0) active.height().toDouble() / pixArray.height else 1.0
            CameraIntrinsics(focal.toDouble(), phys.width * fx, phys.height * fy)
        }.getOrNull()
    }
}
