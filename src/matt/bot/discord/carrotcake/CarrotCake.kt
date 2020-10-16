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
import kotlin.system.exitProcess

lateinit var bot: JDA
    private set

const val botPrefix = "cc!"

val saveFile = File("saveData.json")

val joinedGuilds = mutableMapOf<Guild, GuildInfo>()

var shutdownMode = ExitMode.SHUTDOWN

fun main()
{
    //Logger.getLogger(OkHttpClient::class.java.name).level = Level.FINE
    val token = File("token").readText()
    bot = JDABuilder.create(token, GatewayIntent.getIntents(GatewayIntent.ALL_INTENTS))
        .addEventListeners(UtilityListener(), MessageListener())
        .build()
        .awaitReady()
    bot.addEventListener()
    
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
        
        guildJson.put("id", guildInfo.guild.id)
        guildJson.put("adminRoles", JSONArray(guildInfo.serverAdminRoles.map {it.id}))
        guildJson.put("eventManagerRole", guildInfo.eventManagerRole?.id ?: JSONObject.NULL)
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
        
        event.jda.guilds.forEach {joinedGuilds.putIfAbsent(it, GuildInfo(it))}
    
        if(!saveFile.exists())
            return
    
        val saveData = JSONArray(saveFile.readText())
        saveData.forEach {guildDataObj ->
            val guildData = guildDataObj as JSONObject
            val guild = event.jda.getGuildById(guildData.getString("id"))
            if(guild != null) {
                val guildInfo = joinedGuilds.getOrPut(guild) {GuildInfo(guild)}
                
                guildInfo.serverAdminRoles.addAll(guildData.getJSONArray("adminRoles").mapNotNull {guildInfo.guild.getRoleById(it as String)})
                guildInfo.eventManagerRole = guildData.getStringOrNull("eventManagerRole")?.let {guildInfo.guild.getRoleById(it)}
            }
        }
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
        super.onGuildMessageReactionAdd(event)
    }
    
    override fun onGuildMessageReactionRemove(event: GuildMessageReactionRemoveEvent) {
        super.onGuildMessageReactionRemove(event)
    }
}