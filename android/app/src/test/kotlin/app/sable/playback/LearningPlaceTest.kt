package app.sable.playback

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LearningPlaceTest {
    @Test fun carAndHeadphones_areDifferentPlaces_evenThoughBothAreBluetooth() {
        val car = ListenerLearning.placeOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "CAR MULTIMEDIA")
        val phones = ListenerLearning.placeOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "WH-1000XM4")
        assertNotEquals(car.id, phones.id)
        assertEquals("Bluetooth · CAR MULTIMEDIA", car.label)
    }

    @Test fun speakerAndWired_arePlacesOfTheirOwn_andNamesAreTrimmed() {
        assertEquals("speaker", ListenerLearning.placeOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "SM-A176B").id)
        assertEquals("wired", ListenerLearning.placeOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "x").id)
        assertEquals("bt:Buds", ListenerLearning.placeOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "  Buds ").id)
        assertEquals("usb", ListenerLearning.placeOf(AudioDeviceInfo.TYPE_USB_HEADSET, " ").id)
    }
}
