package com.mineygg.questmate

import android.app.Application
import com.mineygg.questmate.core.ClientConfig
import com.mineygg.questmate.core.Connectivity
import com.mineygg.questmate.core.DeviceHeaders
import com.mineygg.questmate.core.DeviceProfile
import com.mineygg.questmate.core.DiscordLocale
import com.mineygg.questmate.core.Notifier
import com.mineygg.questmate.core.RunBus
import com.mineygg.questmate.data.Store
import com.mineygg.questmate.work.Scheduler

class QuestMateApp : Application() {
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        ClientConfig.init(this, store)
        DeviceProfile.init(this, store)
        DeviceHeaders.init(this)
        DiscordLocale.ipCountry = store.ipCountry
        Notifier.createChannels(this)
        Connectivity.start(this)
        RunBus.restore(store.savedLog)
        Scheduler.schedule(this, store.settings.value, replace = false)
    }
}
