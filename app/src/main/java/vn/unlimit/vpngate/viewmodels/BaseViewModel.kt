package vn.unlimit.vpngate.viewmodels

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData

open class BaseViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        const val TAG = "BaseViewModel"
        const val ITEM_PER_PAGE = 30
        const val PARAMS_USER_PLATFORM = "Android"
        const val MAX_RETRY_COUNT = 3
    }

    var isLoading: MutableLiveData<Boolean> = MutableLiveData(false)

    fun handleExpiresError(errorCode: Int?, activity: Activity?) {
        // Session-based paid server feature has been removed.
    }
}