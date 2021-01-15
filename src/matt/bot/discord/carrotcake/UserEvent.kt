package matt.bot.discord.carrotcake

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.Role
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.util.*

class UserEvent(var message: Message, var startingTime: LocalDateTime, var duration: Duration, var repeatType: RecurringType, var title: String, var details: String, val uuid: UUID, var pingMessage: Message? = null) {
    companion object {
        fun createEmbed(title: String, details: String, start: LocalDateTime, duration: Duration, repeatType: RecurringType, uuid: UUID): MessageEmbed {
            val embedBuilder = EmbedBuilder()
            embedBuilder.setTitle(title).setDescription(details).setImage(bot.selfUser.avatarUrl)
            embedBuilder.addField("Date", prettyPrintDate(start), false)
            embedBuilder.addField("Duration", prettyPrintDuration(duration), true)
            embedBuilder.addField("Time until event", prettyPrintDuration(Duration.between(LocalDateTime.now(), start).coerceAtLeast(Duration.ZERO)), true)
            embedBuilder.addField("Repeating", repeatType.toString().toLowerCase().capitalize(), true)
            embedBuilder.addField("UUID", uuid.toString(), false)
            embedBuilder.setFooter("React to this message with $eventEmote for a notification when the event starts")
            
            return embedBuilder.build()
        }
    }
    
    val guild = message.guild
    
    init {
        val roleName = "$title $uuid"
        if(guild.getRolesByName(roleName, true).isEmpty())
            guild.createRole().setName(roleName).queue()
    }
    
    val role: Role by lazy {
        retry(10) {
            val role = guild.getRolesByName("$title $uuid", true).firstOrNull()
            if(role == null) {
                Thread.sleep(100)
                throw Throwable()
            }
            else
                role
        }
    }
    val file = File(eventDir, uuid.toString())
    
    fun updateRole() {
        role.manager.setName("$title $uuid").queue()
    }
    
    fun updateEmbed() {
        val embed = createEmbed(title, details, startingTime, duration, repeatType, uuid)
        message.editMessage(embed).queue()
    }
    
    fun saveEvent() {
        eventDir.mkdirs()
        
        val eventData = JSONObject()
        eventData.put("uuid", uuid.toString())
        eventData.put("start", startingTime.toString())
        eventData.put("duration", duration.toString())
        eventData.put("repeating", repeatType.toString())
        eventData.put("title", title)
        eventData.put("details", details)
        eventData.put("channelId", message.channel.id)
        eventData.put("messageId", message.id)
        eventData.put("pingMessageId", pingMessage?.id ?: JSONObject.NULL)
        
        file.writeText(eventData.toString(4))
    }
}

enum class RecurringType {
    NEVER, DAILY, WEEKLY, MONTHLY, YEARLY
}