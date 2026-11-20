package com.regis.boot

import java.util.concurrent.CopyOnWriteArrayList

object Boot {

    @JvmField @Volatile var stage: Int = 0
    @JvmField @Volatile var message: String = ""

    private val history = CopyOnWriteArrayList<String>()

    @JvmStatic
    fun status(stage: Int, message: String?) {
        this.stage = stage
        val text = message ?: ""
        this.message = text
        if (text.isNotBlank()) {
            history.add(text)
            while (history.size > 200) history.removeAt(0)
        }
        android.util.Log.i("LarpBoot", "stage=$stage $text")
    }

    @JvmStatic
    fun lines(): List<String> = history.toList()

    @JvmStatic
    fun clear() {
        stage = 0
        message = ""
        history.clear()
    }
}
