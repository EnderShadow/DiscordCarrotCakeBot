package matt.bot.discord.carrotcake

import java.time.Duration
import java.time.LocalDateTime

class EventThread: Thread("discord-user-event-thread") {
    init {
        isDaemon = true
    }
    
    override fun run() {
        while(true) {
            var sleep = true
            synchronized(eventLock) {
                val event: UserEvent? = events.peek()
                if(event != null && event.startingTime <= LocalDateTime.now()) {
                    // remove top item
                    events.poll()
                    RunningEventThread(event).start()
                    sleep = false
                }
            }
            if(sleep)
                sleep(30_000)
        }
    }
}

private class RunningEventThread(private val userEvent: UserEvent): Thread("event-delete-thread-${userEvent.uuid}") {
    init {
        isDaemon = true
    }
    
    override fun run() {
        val guildInfo = joinedGuilds[userEvent.guild]!!
        val eventStartMessage = userEvent.pingMessage ?: run {
            val eventStartMessage = guildInfo.eventChannel?.sendMessage("${userEvent.role.asMention} ${userEvent.title} is now starting")?.complete()
            userEvent.pingMessage = eventStartMessage
            userEvent.saveEvent()
            eventStartMessage
        }
        
        val remaining = Duration.between(LocalDateTime.now(), userEvent.startingTime + userEvent.duration)
        if(!remaining.isNegative && !remaining.isZero)
            sleep(remaining.toMillis())
        
        eventStartMessage?.delete()?.queue()
        userEvent.role.delete().queue()
        userEvent.message.delete().queue()
        userEvent.file.delete()
    }
}