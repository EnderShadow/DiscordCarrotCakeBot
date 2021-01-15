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
                val event: UserEvent? = events.firstOrNull()
                if(event != null && event.startingTime <= LocalDateTime.now()) {
                    // remove top item
                    events.pollFirst()
                    RunningEventThread(event).start()
                    sleep = false
                }
                if(sleep) {
                    events.forEach {
                        val timeUntilEvent = Duration.between(LocalDateTime.now(), it.startingTime)
                        if(timeUntilEvent < Duration.ofMinutes(15))
                            it.updateEmbed()
                        else if(timeUntilEvent.toMinutes() % 15 == 0L && timeUntilEvent.seconds % 60 < 30)
                            it.updateEmbed()
                    }
                }
            }
            if(sleep) {
                sleep(30_000)
            }
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
        
        when(userEvent.repeatType) {
            RecurringType.NEVER -> {
                userEvent.message.delete().queue()
                userEvent.role.delete().queue()
                userEvent.file.delete()
            }
            RecurringType.DAILY -> {
                while(userEvent.startingTime < LocalDateTime.now())
                    userEvent.startingTime = userEvent.startingTime.plusDays(1)
                userEvent.pingMessage = null
                userEvent.saveEvent()
                userEvent.updateEmbed()
            }
            RecurringType.WEEKLY -> {
                while(userEvent.startingTime < LocalDateTime.now())
                    userEvent.startingTime = userEvent.startingTime.plusWeeks(1)
                userEvent.pingMessage = null
                userEvent.saveEvent()
                userEvent.updateEmbed()
            }
            RecurringType.MONTHLY -> {
                while(userEvent.startingTime < LocalDateTime.now())
                    userEvent.startingTime = userEvent.startingTime.plusMonths(1)
                userEvent.pingMessage = null
                userEvent.saveEvent()
                userEvent.updateEmbed()
            }
            RecurringType.YEARLY -> {
                while(userEvent.startingTime < LocalDateTime.now())
                    userEvent.startingTime = userEvent.startingTime.plusYears(1)
                userEvent.pingMessage = null
                userEvent.saveEvent()
                userEvent.updateEmbed()
            }
        }
        
        if(userEvent.repeatType != RecurringType.NEVER) {
            synchronized(eventLock) {
                events.add(userEvent)
            }
        }
    }
}