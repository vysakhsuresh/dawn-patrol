package com.dawnpatrol.game

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/**
 * Hosts GameView and nothing else - no XML layout. GameView itself handles
 * pausing on window focus loss (see its onWindowFocusChanged override),
 * which is the thing that actually matters: onPause alone misses the
 * notification shade stealing focus.
 */
class MainActivity : Activity() {

    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        gameView = GameView(this)
        setContentView(gameView)
    }
}
