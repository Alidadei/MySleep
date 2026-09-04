package org.fossify.clock.helpers

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Thin wrapper around [CameraManager.setTorchMode], which controls the flash LED
 * without opening the camera and without the CAMERA permission (API 23+).
 *
 * The LED has no real brightness levels, so "sunrise" fading is done elsewhere
 * ([org.fossify.clock.services.SunriseService]) by duty-cycle modulating this switch.
 */
object TorchHelper {

    private var flashCameraId: String? = null
    private var flashCheckDone = false

    fun hasFlash(context: Context): Boolean = findFlashCameraId(context) != null

    /**
     * @return true if the torch state was changed, false if the device has no flash
     * or the camera unit is currently busy (e.g. another app holds the camera).
     */
    fun setTorch(context: Context, on: Boolean): Boolean {
        val cameraId = findFlashCameraId(context) ?: return false
        val cameraManager = getCameraManager(context)
        return try {
            cameraManager.setTorchMode(cameraId, on)
            true
        } catch (e: CameraAccessException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun getCameraManager(context: Context) =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun findFlashCameraId(context: Context): String? {
        if (flashCheckDone) {
            return flashCameraId
        }

        flashCheckDone = true
        try {
            val cameraManager = getCameraManager(context)
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val hasFlashUnit =
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val isBackFacing =
                    characteristics.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK

                if (hasFlashUnit && (isBackFacing || flashCameraId == null)) {
                    flashCameraId = cameraId
                    if (isBackFacing) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            flashCameraId = null
        }

        return flashCameraId
    }
}
