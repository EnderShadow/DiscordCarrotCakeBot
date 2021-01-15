package matt.bot.discord.carrotcake

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.events.ReadyEvent
import net.dv8tion.jda.api.events.ShutdownEvent
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionAddEvent
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionRemoveEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import kotlin.system.exitProcess

lateinit var bot: JDA
    private set

const val botPrefix = "cc!"
const val eventEmote = "✅"

val saveFile = File("saveData.json")
val eventDir = File("events")

val joinedGuilds = mutableMapOf<Guild, GuildInfo>()
val events = PriorityQueue<UserEvent>{ue1, ue2 -> ue1.startingTime.compareTo(ue2.startingTime)}
val eventThread = EventThread()
val eventLock = Any()

var shutdownMode = ExitMode.SHUTDOWN

fun main()
{
    //Logger.getLogger(OkHttpClient::class.java.name).level = Level.FINE
    val token = File("token").readText()
    bot = JDABuilder.create(token, GatewayIntent.getIntents(GatewayIntent.ALL_INTENTS))
        .addEventListeners(UtilityListener(), MessageListener())
        .build()
    bot.awaitReady()
    
    while(true)
    {
        try
        {
            commandLine(bot)
        }
        catch(e: Exception)
        {
            e.printStackTrace()
        }
    }
}

fun save()
{
    val saveData = JSONArray(joinedGuilds.values.map {guildInfo ->
        val guildJson = JSONObject()
        
        guildJson.put("guildId", guildInfo.guild.id)
        guildJson.put("adminRoles", JSONArray(guildInfo.serverAdminRoles.map {it.id}))
        guildJson.put("eventChannel", guildInfo.eventChannel?.id ?: JSONObject.NULL)
    })
    
    saveFile.writeText(saveData.toString(4))
}

class UtilityListener: ListenerAdapter()
{
    override fun onReady(event: ReadyEvent)
    {
        event.jda.isAutoReconnect = true
        println("Logged in as ${event.jda.selfUser.name}\n${event.jda.selfUser.id}\n-----------------------------")
        event.jda.presence.activity = Activity.playing("cc!help for a list of commands")
        
        // start loading guild data
        
        event.jda.guilds.forEach {joinedGuilds.putIfAbsent(it, GuildInfo(it))}
    
        if(!saveFile.exists())
            return
    
        val saveData = JSONArray(saveFile.readText())
        saveData.forEach {guildDataObj ->
            val guildData = guildDataObj as JSONObject
            val guild = event.jda.getGuildById(guildData.getString("guildId"))
            if(guild != null) {
                val guildInfo = joinedGuilds.getOrPut(guild) {GuildInfo(guild)}
                
                guildInfo.serverAdminRoles.addAll(guildData.getJSONArray("adminRoles").mapNotNull {guildInfo.guild.getRoleById(it as String)})
                guildInfo.eventChannel = guildData.getStringOrNull("eventChannel")?.let {guildInfo.guild.getTextChannelById(it)}
            }
        }
        
        // done loading guild data
        
        // start loading event data
        
        eventDir.mkdirs()
        eventDir.listFiles()?.run {
            asSequence().filter {it.isFile}.forEach {file ->
                val eventData = JSONObject(file.readText())
                val uuidStr = eventData.getString("uuid")
                val uuid = UUID.fromString(uuidStr)
                val start = LocalDateTime.parse(eventData.getString("start"))
                val duration = Duration.parse(eventData.getString("duration"))
                val repeating = RecurringType.valueOf(eventData.getString("repeating"))
                val title = eventData.getString("title")
                val details = eventData.getString("details")
    
                val textChannel = event.jda.getTextChannelById(eventData.getString("channelId"))
                val message = textChannel?.retrieveMessageById(eventData.getString("messageId"))?.complete()
                val pingMessage = eventData.getStringOrNull("pingMessageId")?.let {textChannel?.retrieveMessageById(it)?.complete()}
                
                if(start + duration <= LocalDateTime.now()) {
                    println("Event has already ended.")
                    pingMessage?.delete()?.queue()
                    if(repeating == RecurringType.NEVER || (textChannel == null && message == null)) {
                        println("Event does not repeat or message is unable to be found or created. Deleting event.")
                        message?.delete()?.queue()
                        file.delete()
                        val guildInfo = textChannel?.guild?.let(joinedGuilds::get)
                        if(guildInfo != null) {
                            val role = guildInfo.guild.getRolesByName("$title $uuidStr", true).firstOrNull()
                            role?.delete()?.queue()
                        }
                    }
                    else {
                        println("Updating event with new start date.")
                        var newStart = start
                        when(repeating) {
                            RecurringType.NEVER -> Unit // already taken care of above
                            RecurringType.DAILY -> {
                                while(newStart < LocalDateTime.now())
                                    newStart = newStart.plusDays(1)
                            }
                            RecurringType.WEEKLY -> {
                                while(newStart < LocalDateTime.now())
                                    newStart = newStart.plusWeeks(1)
                            }
                            RecurringType.MONTHLY -> {
                                while(newStart < LocalDateTime.now())
                                    newStart = newStart.plusMonths(1)
                            }
                            RecurringType.YEARLY -> {
                                while(newStart < LocalDateTime.now())
                                    newStart = newStart.plusYears(1)
                            }
                        }
                        
                        val messageToUse = message ?: textChannel.sendMessage(UserEvent.createEmbed(title, details, newStart, duration, repeating, uuid)).complete()
                        val userEvent = UserEvent(messageToUse, newStart, duration, repeating, title, details, uuid, null)
                        userEvent.saveEvent()
                        userEvent.updateEmbed()
                        events.add(userEvent)
                    }
                }
                else if(message == null && textChannel == null) {
                    System.err.println("Failed to retrieve message and textChannel from discord servers. Event is no longer valid. Cleaning up.")
                    file.delete()
                    pingMessage?.delete()?.queue()
                    val guildInfo = textChannel?.guild?.let(joinedGuilds::get)
                    if(guildInfo != null) {
                        val role = guildInfo.guild.getRolesByName("$title $uuidStr", true).firstOrNull()
                        role?.delete()?.queue()
                    }
                }
                else {
                    if(message == null)
                        println("Failed to retrieve message, recreating message.")
                    
                    val messageToUse = message ?: textChannel.sendMessage(UserEvent.createEmbed(title, details, start, duration, repeating, uuid)).complete()
                    val userEvent = if(start > LocalDateTime.now()) {
                        // if the event was manually rescheduled such that it hasn't started yet, delete the ping message
                        pingMessage?.delete()?.queue()
                        UserEvent(messageToUse, start, duration, repeating, title, details, uuid, null)
                    }
                    else {
                        UserEvent(messageToUse, start, duration, repeating, title, details, uuid, pingMessage)
                    }
                    
                    if(message == null)
                        userEvent.saveEvent()
                    
                    events.add(userEvent)
                }
            }
        }
        
        // done loading event data
        
        // synchronization is now required when accessing the event queue
        eventThread.start()
    }
    
    override fun onGuildJoin(event: GuildJoinEvent) {
        joinedGuilds[event.guild] = GuildInfo(event.guild)
    }
    
    override fun onGuildLeave(event: GuildLeaveEvent) {
        joinedGuilds.remove(event.guild)
    }

    override fun onShutdown(event: ShutdownEvent)
    {
        save()
        exitProcess(shutdownMode.ordinal)
    }
}

class MessageListener: ListenerAdapter()
{
    override fun onMessageReceived(event: MessageReceivedEvent)
    {
        if(event.author.isBot)
            return

        val tokenizer = Tokenizer(event.message.contentRaw)
        if(!tokenizer.hasNext())
            return

        val firstToken = tokenizer.next()
        if(firstToken.tokenType == TokenType.COMMAND)
        {
            runCommand(firstToken.tokenValue, tokenizer, event.message)
        }
    }
    
    override fun onGuildMessageReactionAdd(event: GuildMessageReactionAddEvent) {
        if(event.user.isBot)
            return
        synchronized(eventLock) {
            val role = events.firstOrNull {it.message.id == event.messageId}?.role
            if(role != null)
                event.guild.addRoleToMember(event.member, role).queue()
        }
    }
    
    override fun onGuildMessageReactionRemove(event: GuildMessageReactionRemoveEvent) {
        if(event.user?.isBot != false)
            return
        synchronized(eventLock) {
            val role = events.firstOrNull {it.message.id == event.messageId}?.role
            if(role != null && event.member != null)
                event.guild.removeRoleFromMember(event.member!!, role).queue()
        }
    }
}