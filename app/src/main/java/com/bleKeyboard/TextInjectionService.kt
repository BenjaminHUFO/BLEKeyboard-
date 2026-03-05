package com.blekeyboard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TextInjectionService : AccessibilityService() {

    companion object {
        const val ACTION_INJECT_TEXT = "com.blekeyboard.INJECT_TEXT"
        const val EXTRA_TEXT         = "text"
        var instance: TextInjectionService? = null
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_INJECT_TEXT) {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                injectText(text)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerReceiver(receiver, IntentFilter(ACTION_INJECT_TEXT))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    /**
     * Injecte le texte dans le champ actuellement focalisé.
     * Stratégie : on récupère le nœud focalisé et on lui envoie
     * le texte via ACTION_SET_TEXT (API 21+).
     * Si le champ a déjà du contenu, on ajoute à la suite.
     */
    private fun injectText(text: String) {
        val root = rootInActiveWindow ?: return

        // Cherche le nœud focalisé (champ texte actif)
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return

        val args = Bundle()
        // Ajoute à la suite du texte existant si besoin
        val existing = focused.text?.toString() ?: ""
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            if (existing.isEmpty()) text else "$existing$text"
        )
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
        root.recycle()
    }
}
