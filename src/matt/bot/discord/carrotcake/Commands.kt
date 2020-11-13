package matt.bot.discord.carrotcake

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.TextChannel
import java.time.Duration
import java.time.LocalDateTime
import java.util.*

fun runCommand(command: String, tokenizer: Tokenizer, sourceMessage: Message)
{
    if(!sourceMessage.channelType.isGuild)
        Command[command].takeIf {it.allowedInPrivateChannel}?.invoke(tokenizer, sourceMessage)
    else
        Command[command].let {
            if(!it.requiresAdmin || isServerAdmin(sourceMessage.member!!))
                it(tokenizer, sourceMessage)
            else
                sourceMessage.channel.sendMessage("${sourceMessage.member!!.asMention} You don't have permission to run this command.").queue()
        }
}

@Suppress("unused")
sealed class Command(val prefix: String, val requiresAdmin: Boolean = false, val allowedInPrivateChannel: Boolean = false)
{
    companion object
    {
        private val commands = mutableMapOf<String, Command>()
        private val noopCommand: Command
        
        init
        {
            Command::class.sealedSubclasses.asSequence().map {it.constructors.first().call()}.forEach {commands[it.prefix] = it}
            noopCommand = commands.remove("noop")!!
        }
        
        operator fun get(prefix: String) = commands.getOrDefault(prefix, noopCommand)
    }
    
    abstract fun helpMessage(): String
    abstract operator fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
    
    class NoopCommand: Command("noop", allowedInPrivateChannel = true)
    {
        override fun helpMessage() = ""
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message) {}
    }
    
    class Say: Command("say", true)
    {
        override fun helpMessage() = """`${botPrefix}say` __Makes the bot say something__
            |
            |**Usage:** ${botPrefix}say [text]
            |              ${botPrefix}say [text] !tts
            |
            |**Examples:**
            |`${botPrefix}say hello world` makes the bot say 'hello world'
            |`${botPrefix}say hello world !tts` makes the bot say 'hello world' with tts
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            var content = tokenizer.remainingTextAsToken.tokenValue
            val tts = content.endsWith("!tts")
            if(tts)
                content = content.substring(0, content.length - 4).trim()
            if(content.isNotEmpty())
            {
                sourceMessage.channel.sendMessage(content).tts(tts).queue()
                sourceMessage.delete().queue()
                println("${sourceMessage.author.name} made me say \"$content\"")
            }
            else
            {
                sourceMessage.channel.sendMessage("I can't say blank messages").queue()
            }
        }
    }
    
    class Config: Command("config", true) {
        override fun helpMessage() = """`${botPrefix}say` __Allows you to configure the bot__
            |
            |**Usage:** ${botPrefix}config list
            |              ${botPrefix}config get [option]
            |              ${botPrefix}config set [option] [value]
            |
            |**Examples:**
            |`${botPrefix}config list` lists all the configuration options and their values
            |`${botPrefix}config get eventChannel` display the currently set channel for events
            |`${botPrefix}config set eventChannel #channelHere` sets the event channel to #channelHere
        """.trimMargin()
    
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            val mode = if(tokenizer.hasNext()) tokenizer.next().rawValue else return
            val guildInfo = joinedGuilds[sourceMessage.guild]!!
            when(mode) {
                "list" -> {
                    val message = "eventChannel: ${guildInfo.eventChannel?.asMention ?: "none"}"
                    
                    sourceMessage.channel.sendMessage(message).allowedMentions(emptyList()).queue()
                }
                "get" -> {
                    val option = if(tokenizer.hasNext()) tokenizer.next().rawValue else return
                    val message = when(option) {
                        "eventChannel" -> {
                            "eventChannel: ${guildInfo.eventChannel?.asMention ?: "none"}"
                        }
                        else -> return
                    }
                    
                    sourceMessage.channel.sendMessage(message).allowedMentions(emptyList()).queue()
                }
                "set" -> {
                    val option = if(tokenizer.hasNext()) tokenizer.next().rawValue else return
                    val newValue = if(tokenizer.hasNext()) tokenizer.next() else return
                    when(option) {
                        "eventChannel" -> {
                            if(newValue.tokenType != TokenType.TEXT_CHANNEL) {
                                sourceMessage.channel.sendMessage("The new value for eventManagerRole must be a text channel").queue()
                            }
                            else {
                                guildInfo.eventChannel = newValue.objValue as TextChannel
                                save()
                                sourceMessage.channel.sendMessage("eventChannel was successfully updated").queue()
                            }
                        }
                    }
                }
            }
        }
    }
    
    class Admin: Command("admin", true)
    {
        override fun helpMessage() = """`l!admin` __Used for managing who can administrate the bot__
            |
            |**Usage:** l!admin list
            |              l!admin add [role] ...
            |              l!admin remove [role] ...
            |
            |The server owner can always administrate the bot
            |
            |**Examples:**
            |`l!admin list` lists the roles that can currently manage the bot
            |`l!admin add @Admin @Moderator` adds the @Admin and @Moderator role to the list of roles that can administrate the bot
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            if(tokenizer.hasNext())
            {
                val guildInfo = joinedGuilds[sourceMessage.guild]!!
                when(tokenizer.next().tokenValue)
                {
                    "list" -> {
                        if(guildInfo.serverAdminRoles.isNotEmpty())
                            sourceMessage.channel.sendMessage(guildInfo.serverAdminRoles.joinToString(" ") {it.asMention}).allowedMentions(emptyList()).queue()
                        else
                            sourceMessage.channel.sendMessage("No roles are registered as a bot admin").queue()
                    }
                    "add" -> {
                        if(tokenizer.hasNext())
                        {
                            guildInfo.serverAdminRoles.addAll(tokenizer.asSequence().filter {it.tokenType == TokenType.ROLE}.mapNotNull {sourceMessage.guild.getRoleById(it.tokenValue)})
                            save()
                        }
                    }
                    "remove" -> {
                        if(guildInfo.serverAdminRoles.removeAll(tokenizer.asSequence().filter {it.tokenType == TokenType.ROLE}.mapNotNull {sourceMessage.guild.getRoleById(it.tokenValue)}))
                            save()
                    }
                }
            }
        }
    }
    
    class ClearRoles: Command("clearRoles", true) {
        override fun helpMessage() = """`l!clearRoles` __Used for clearing roles created by the bot__
            |
            |**Usage:** l!clearRoles
            |
            |**Examples:**
            |`l!clearRoles` clears all unused roles created by this bot
        """.trimMargin()
    
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            val guildInfo = joinedGuilds[sourceMessage.guild]!!
            val roles = guildInfo.guild.roles.filter {it.name.matches(Regex("^.*[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$"))}.toMutableList()
            roles.removeIf {role ->
                synchronized(eventLock) {
                    events.any {it.role == role}
                }
            }
            roles.forEach {it.delete().queue()}
            sourceMessage.channel.sendMessage("Deleted all unused roles created by the bot").queue()
        }
    }
    
    class Event: Command("event", true) {
        override fun helpMessage() = """`${botPrefix}help` __Used for managing events__
            |
            |**Usage:** ${botPrefix}event list
            |              ${botPrefix}event create [title] [start date] [duration] [event details]
            |              ${botPrefix}event edit [uuid] [options...]
            |              ${botPrefix}event delete [uuid]
            |              ${botPrefix}event refreshEmbed [uuid]
            |
            |**NOTE**
            |Dates and durations are specified using ISO 8601 which can be found here: https://en.wikipedia.org/wiki/ISO_8601
            |
            |**Editing options**
            |You can use any combination of the following for editing events
            |   title [new title]
            |   start [new start date]
            |   duration [new duration]
            |   details [new event details]
            |
            |**Examples:**
            |`${botPrefix}event list` lists all current events sorted by their start date
            |`${botPrefix}event create "A Movie" 2020-10-16T19:30 PT2H Watch this great movie` creates an event titled 'A Movie' which starts on October 16, 2020, lasts 2 hours, and has the description 'Watch this great movie'
            |`${botPrefix}event edit 00000000-0000-0000-0000-000000000000 title "A Great Movie" details "A better description"` edits the event with uuid 00000000-0000-0000-0000-000000000000 and sets the title to 'A Great Movie' and changes the description to 'A better description'
            |`${botPrefix}event delete 00000000-0000-0000-0000-000000000000` deletes the event with uuid 00000000-0000-0000-0000-000000000000
            |`${botPrefix}event refreshEmbed 00000000-0000-0000-0000-000000000000` updates the embed for the event with uuid 00000000-0000-0000-0000-000000000000
        """.trimMargin()
    
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message) {
            val mode = if(tokenizer.hasNext()) tokenizer.next().rawValue else return
            when(mode) {
                "list" -> {
                    synchronized(eventLock) {
                        if(events.isEmpty()) {
                            sourceMessage.channel.sendMessage("There are currently no events").queue()
                        }
                        else {
                            val message = events.joinToString("\n") {
                                "`${it.uuid}`: '${it.title}' starting on ${prettyPrintDate(it.startingTime)} and lasting ${prettyPrintDuration(it.duration)}"
                            }
                            splitAt2000(message).forEach {
                                sourceMessage.channel.sendMessage(it).queue()
                            }
                        }
                    }
                }
                "create" -> {
                    val title = if(tokenizer.hasNext()) tokenizer.next().rawValue else return
                    val startStr = if(tokenizer.hasNext()) tokenizer.next().rawValue else return
                    val durationStr = if(tokenizer.hasNext()) tokenizer.next().rawValue else return
                    val details = if(tokenizer.hasNext()) tokenizer.remainingTextAsToken.rawValue else return
                    
                    val start = LocalDateTime.parse(startStr)
                    val duration = Duration.parse(durationStr)
                    
                    val uuid = UUID.randomUUID()
                    val embed = UserEvent.createEmbed(title, details, start, duration, uuid)
                    
                    joinedGuilds[sourceMessage.guild]!!.eventChannel?.sendMessage(embed)?.queue {
                        it.addReaction(eventEmote).queue()
                        val event = UserEvent(it, start, duration, title, details, uuid)
                        event.saveEvent()
                        synchronized(eventLock) {
                            events.add(event)
                        }
                    }
                }
                "edit" -> {
                    val uuid = if(tokenizer.hasNext()) tokenizer.next().objValue else return
                    if(uuid !is UUID)
                        return
                    
                    var newTitle: String? = null
                    var newDetails: String? = null
                    var newStart: LocalDateTime? = null
                    var newDuration: Duration? = null
                    
                    while(tokenizer.hasNext()) {
                        when(tokenizer.next().rawValue) {
                            "title" -> {
                                newTitle = if(tokenizer.hasNext()) tokenizer.next().rawValue else return
                            }
                            "details" -> {
                                newDetails = if(tokenizer.hasNext()) tokenizer.next().rawValue else return
                            }
                            "start" -> {
                                val newStartStr = if(tokenizer.hasNext()) tokenizer.next().rawValue else return
                                newStart = LocalDateTime.parse(newStartStr)
                            }
                            "duration" -> {
                                val newDurationStr = if(tokenizer.hasNext()) tokenizer.next().rawValue else return
                                newDuration = Duration.parse(newDurationStr)
                            }
                        }
                    }
                    
                    val needToUpdateRole = newTitle != null
                    val needToUpdateEmbed = newTitle != null || newDetails != null || newStart != null || newDuration != null
                    
                    synchronized(eventLock) {
                        val event = events.firstOrNull {it.uuid == uuid}
                        if(event != null) {
                            if(newTitle != null)
                                event.title = newTitle
                            if(newDetails != null)
                                event.details = newDetails
                            if(newStart != null)
                                event.startingTime = newStart
                            if(newDuration != null)
                                event.duration = newDuration
                            
                            if(needToUpdateRole)
                                event.updateRole()
                            if(needToUpdateEmbed) {
                                event.updateEmbed()
                                event.saveEvent()
                            }
                            
                            sourceMessage.channel.sendMessage("The event with uuid $uuid has been updated").queue()
                        }
                        else {
                            sourceMessage.channel.sendMessage("No event with uuid $uuid was found").queue()
                        }
                    }
                }
                "delete" -> {
                    val uuid = if(tokenizer.hasNext()) tokenizer.remainingTextAsToken.objValue else return
                    if(uuid !is UUID)
                        return
                    synchronized(eventLock) {
                        events.firstOrNull {it.uuid == uuid}?.let {
                            events.remove(it)
                            it.role.delete().queue()
                            it.message.delete().queue()
                            it.file.delete()
                            sourceMessage.channel.sendMessage("The event with uuid $uuid has been removed").queue()
                        } ?: sourceMessage.channel.sendMessage("No event with uuid $uuid was found").queue()
                    }
                }
                "refreshEmbed" -> {
                    val uuid = if(tokenizer.hasNext()) tokenizer.next().objValue else return
                    if(uuid !is UUID)
                        return
                    
                    synchronized(eventLock) {
                        events.firstOrNull {it.uuid == uuid}?.updateEmbed() ?: sourceMessage.channel.sendMessage("No event with uuid $uuid was found").queue()
                        sourceMessage.channel.sendMessage("The event with uuid $uuid has had its embed updated").queue()
                    }
                }
            }
        }
    }
    
    class Help: Command("help", allowedInPrivateChannel = true)
    {
        override fun helpMessage() = """`${botPrefix}help` __Displays a list of commands. Provide a command to get its info__
            |
            |**Usage:** ${botPrefix}help
            |              ${botPrefix}help [command]
            |
            |**Examples:**
            |`${botPrefix}help` displays a list of all commands
            |`${botPrefix}help say` displays the help info for the say command
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message)
        {
            val (adminCommands, normalCommands) = commands.values.splitAndMap(Command::requiresAdmin) {it.prefix}
            val message = if(!tokenizer.hasNext())
            {
                """```bash
                    |'command List'```
                    |
                    |Use `!help [command]` to get more info on a specific command, for example: `${botPrefix}help say`
                    |
                    |**Standard Commands**
                    |${normalCommands.joinToString(" ") {"`$it`"}}
                    |
                    |**Admin Commands**
                    |${adminCommands.joinToString(" ") {"`$it`"}}
                """.trimMargin()
            }
            else
            {
                val command = tokenizer.next().tokenValue
                commands[command]?.helpMessage() ?: "Command '$command' was not found."
            }
            sourceMessage.channel.sendMessage(message).queue()
        }
    }
}