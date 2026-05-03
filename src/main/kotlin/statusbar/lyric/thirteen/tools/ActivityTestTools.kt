package statusbar.lyric.thirteen.tools

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import statusbar.lyric.thirteen.data.Data

@SuppressLint("StaticFieldLeak")
object ActivityTestTools {

    fun Context.getClass() {
        sendBroadcast(Intent("TestReceiver").apply {
            putExtra("Type", "GetClass")
        })
    }

    fun Context.receiveClass(dataList: ArrayList<Data>) {
        sendBroadcast(Intent("AppTestReceiver").apply {
            putExtra("Type", "ReceiveClass")
            putExtra("DataList", dataList)
        })
    }

    fun Context.showView(data: Data) {
        sendBroadcast(Intent("TestReceiver").apply {
            putExtra("Type", "ShowView")
            putExtra("Data", data)
        })
    }
}
