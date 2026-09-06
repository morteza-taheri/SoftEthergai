package vn.unlimit.vpngate.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.automode.AutoModeEngine
import vn.unlimit.vpngate.automode.AutoModeState
import vn.unlimit.vpngate.utils.DataUtil

/**
 * Thin bridge between [AutoModeEngine] and the Auto Mode screen.
 * The engine is a process-wide singleton so the run survives
 * fragment navigation.
 */
class AutoModeViewModel(application: Application) : AndroidViewModel(application) {

    val dataUtil: DataUtil = App.instance!!.dataUtil!!

    val state = MutableLiveData<AutoModeState>(AutoModeState.Disconnected)

    init {
        viewModelScope.launch {
            AutoModeEngine.state().collectLatest { state.value = it }
        }
    }

    fun onButtonPressed() = AutoModeEngine.onButtonPressed()

    /** Try next server button: skip the in-flight attempt. */
    fun tryNextServer() = AutoModeEngine.skipToNextServer()
}
