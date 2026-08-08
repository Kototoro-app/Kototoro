package org.skepsun.kototoro.core.parser.tvbox

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TVBoxActionHostActivity : AppCompatActivity() {

	companion object {
		private const val TAG = "TVBoxActionHost"
		private const val EXTRA_REQUEST_ID = "tvbox_action_request_id"
		private val requests = ConcurrentHashMap<String, (TVBoxActionHostActivity) -> Unit>()

		fun start(activity: Activity, request: (TVBoxActionHostActivity) -> Unit) {
			val requestId = UUID.randomUUID().toString()
			requests[requestId] = request
			try {
				Log.i(TAG, "Starting dedicated TVBox action host")
				activity.startActivity(
					Intent(activity, TVBoxActionHostActivity::class.java)
						.putExtra(EXTRA_REQUEST_ID, requestId),
				)
			} catch (error: Throwable) {
				requests.remove(requestId)
				throw error
			}
		}
	}

	private val requestId by lazy(LazyThreadSafetyMode.NONE) {
		intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
	}
	private var dispatched = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (requestId.isBlank() || !requests.containsKey(requestId)) {
			finish()
		}
	}

	override fun onResume() {
		super.onResume()
		if (dispatched || isFinishing) return
		dispatched = true
		window.decorView.post {
			val request = requests.remove(requestId)
			if (request == null) {
				finish()
				return@post
			}
			Log.i(TAG, "Executing TVBox action with a dedicated activity host")
			request(this)
		}
	}

	fun complete() {
		runOnUiThread {
			if (!isFinishing && !isDestroyed) finish()
		}
	}

	override fun onDestroy() {
		requests.remove(requestId)
		super.onDestroy()
	}
}
