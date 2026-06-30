package com.sillyclient.plugin

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.sillyclient.MainActivity

/**
 * Capacitor plugin wrapping the SillyClient tavern startup & reading environment.
 *
 * All heavy lifting lives in MainActivity; this plugin is a thin bridge.
 * Events (progress / log / ready) are pushed from MainActivity via [notify].
 */
@CapacitorPlugin(name = "TarvenEnv")
class TarvenEnvPlugin : Plugin() {

    companion object {
        private var instance: TarvenEnvPlugin? = null

        /** Push event to Capacitor JS listeners. Safe from any thread. */
        fun notify(event: String, data: JSObject) {
            val p = instance ?: return
            p.activity?.runOnUiThread {
                p.notifyListeners(event, data)
            }
        }
    }

    override fun load() {
        instance = this
    }

    @PluginMethod
    fun provisionAndStart(call: PluginCall) {
        val act = activity as? MainActivity ?: run {
            call.reject("Not MainActivity")
            return
        }
        act.runOnUiThread { act.provisionAndStart() }
        call.resolve()
    }

    @PluginMethod
    fun enterImmersive(call: PluginCall) {
        val act = activity as? MainActivity ?: run {
            call.reject("Not MainActivity")
            return
        }
        act.runOnUiThread { act.enterTavern() }
        call.resolve()
    }

    @PluginMethod
    fun exitImmersive(call: PluginCall) {
        val act = activity as? MainActivity ?: run {
            call.reject("Not MainActivity")
            return
        }
        act.runOnUiThread { act.exitTavern() }
        call.resolve()
    }

    @PluginMethod
    fun getStatus(call: PluginCall) {
        val act = activity as? MainActivity ?: run {
            call.reject("Not MainActivity")
            return
        }
        val ret = JSObject()
        ret.put("ready", act.isServerReady())
        call.resolve(ret)
    }
}