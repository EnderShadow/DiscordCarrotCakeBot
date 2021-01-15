package matt.bot.discord.carrotcake

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.TextChannel
import java.time.*
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
        override fun helpMessage() = """`${botPrefix}config` __Allows you to configure the bot__
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
            val mode = if(tokenizer.hasNext()) tokenizer.next().tokenValue else return
            val guildInfo = joinedGuilds[sourceMessage.guild]!!
            when(mode) {
                "list" -> {
                    val message = "eventChannel: ${guildInfo.eventChannel?.asMention ?: "none"}"
                    
                    sourceMessage.channel.sendMessage(message).allowedMentions(emptyList()).queue()
                }
                "get" -> {
                    val option = if(tokenizer.hasNext()) tokenizer.next().tokenValue else return
                    val message = when(option) {
                        "eventChannel" -> {
                            "eventChannel: ${guildInfo.eventChannel?.asMention ?: "none"}"
                        }
                        else -> return
                    }
                    
                    sourceMessage.channel.sendMessage(message).allowedMentions(emptyList()).queue()
                }
                "set" -> {
                    val option = if(tokenizer.hasNext()) tokenizer.next().tokenValue else return
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
        override fun helpMessage() = """`${botPrefix}admin` __Used for managing who can administrate the bot__
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
        override fun helpMessage() = """`${botPrefix}clearRoles` __Used for clearing roles created by the bot__
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
        override fun helpMessage() = """`${botPrefix}event` __Used for managing events__
            |
            |**Usage:** ${botPrefix}event list
            |              ${botPrefix}event create [name] on [start date] at [start time] lasting [duration] repeating [never | daily | weekly | monthly | yearly] with description [event details]
            |              ${botPrefix}event create [name] on [start date] at [start time] lasting [duration] repeating [never | daily | weekly | monthly | yearly]
            |              ${botPrefix}event create [name] on [start date] at [start time] lasting [duration] with description [event details]
            |              ${botPrefix}event create [name] on [start date] at [start time] lasting [duration]
            |              ${botPrefix}event edit [uuid] [options...]
            |              ${botPrefix}event delete [uuid]
            |              ${botPrefix}event refreshEmbed [uuid]
            |
            |**NOTE**
            |Dates are of the form "Month day year" (eg. January 2 2021)
            |
            |Times are of the form "hour" or "hour:minute" with an optional AM or PM designator (eg. 4:38 pm)
            |   24 hour time is supported and does not use an AM or PM designator
            |
            |Durations are of the form "x days y hours z minutes". You can leave out values that are equal to 0 (eg. 4 hours)
            |
            |**Editing options**
            |You can use any combination of the following for editing events
            |   named [new name]
            |   on [new start date]
            |   at [new start time]
            |   lasting [new duration]
            |   repeating [never | daily | weekly | monthly | yearly]
            |   with description [new event details]
            |
            |**Examples:**
            |`${botPrefix}event list` lists all current events sorted by their start date
            |`${botPrefix}event create "A Movie" on October 16 2020 at 7:30 PM lasting 2 hours repeating weekly with description "Watch this great movie"` creates an event named 'A Movie' which starts on October 16, 2020, lasts 2 hours, repeats weekly, and has the description 'Watch this great movie'
            |`${botPrefix}event edit 00000000-0000-0000-0000-000000000000 named "A Great Movie" with description "A better description"` edits the event with uuid 00000000-0000-0000-0000-000000000000 and sets the name to 'A Great Movie' and changes the description to 'A better description'
            |`${botPrefix}event delete 00000000-0000-0000-0000-000000000000` deletes the event with uuid 00000000-0000-0000-0000-000000000000
            |`${botPrefix}event refreshEmbed 00000000-0000-0000-0000-000000000000` updates the embed for the event with uuid 00000000-0000-0000-0000-000000000000
        """.trimMargin()
    
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message) {
            val mode = if(tokenizer.hasNext()) tokenizer.next().tokenValue else return
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
                    createEvent(tokenizer, sourceMessage)
                }
                "edit" -> {
                    editEvent(tokenizer, sourceMessage)
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
    
        /**
         * If the keyword check fails then null is returned. Otherwise the result of the parse function is wrapped with Optional so that the point of failure can be determined.
         * If the keyword match fails then the tokenizer will be in the same state that it was before the call to checkAndParse
         */
        private inline fun <reified T> checkAndParse(tokenizer: Tokenizer, keyword: String, parseFunction: (Tokenizer) -> T?): Optional<T>? {
            tokenizer.mark()
            if(tokenizer.nextOrNull()?.rawValue != keyword) {
                tokenizer.revert()
                return null
            }
            return Optional.ofNullable(parseFunction(tokenizer))
        }
        
        private fun createEvent(tokenizer: Tokenizer, sourceMessage: Message) {
            val name = if(tokenizer.hasNext()) tokenizer.next().tokenValue else return
            
            val date = checkAndParse(tokenizer, "on", this::parseDate)?.orElse(null) ?: let {
                sourceMessage.channel.sendMessage("Failed to parse date.").queue()
                return
            }
            val time = checkAndParse(tokenizer, "at", this::parseTime)?.orElse(null) ?: let {
                sourceMessage.channel.sendMessage("Failed to parse time.").queue()
                return
            }
            val duration = checkAndParse(tokenizer, "lasting", this::parseDuration)?.orElse(null) ?: let {
                sourceMessage.channel.sendMessage("Failed to parse duration.").queue()
                return
            }
            
            // If the keyword match fails, don't repeat. If the optional is empty, then the command is malformed
            val repeatType = (checkAndParse(tokenizer, "repeating", this::parseRepeatType) ?: Optional.of(RecurringType.NEVER)).orElseGet(null) ?: let {
                sourceMessage.channel.sendMessage("Failed to parse repeat type.").queue()
                return
            }
            
            // shadowed implicit lambda parameter warning is meaningless here since it's the same object in both scopes
            @Suppress("NestedLambdaShadowedImplicitParameter")
            // Tokenizer.remainingTextAsToken.tokenValue is never null
            val description = checkAndParse(tokenizer, "with") {
                checkAndParse(it, "description") {
                    it.remainingTextAsToken.tokenValue
                }?.get() ?: let {
                    sourceMessage.channel.sendMessage("Failed to parse description.").queue()
                    return
                } // if tokenizer has "with" but not "description", that is an error.
            }?.get() ?: "" // if "with description" is not provided, use a blank description
            
            val start = date.atTime(time)
            val uuid = UUID.randomUUID()
            val embed = UserEvent.createEmbed(name, description, start, duration, repeatType, uuid)
    
            joinedGuilds[sourceMessage.guild]!!.eventChannel?.sendMessage(embed)?.queue {
                it.addReaction(eventEmote).queue()
                val event = UserEvent(it, start, duration, repeatType, name, description, uuid)
                event.saveEvent()
                synchronized(eventLock) {
                    events.add(event)
                }
                sourceMessage.channel.sendMessage("Event successfully created").queue()
            }
        }
        
        private fun editEvent(tokenizer: Tokenizer, sourceMessage: Message) {
            val uuid = tokenizer.nextOrNull()?.objValue as? UUID ?: let {
                sourceMessage.channel.sendMessage("Invalid UUID.").queue()
                return
            }
    
            var newTitle: String? = null
            var newStartDate: LocalDate? = null
            var newStartTime: LocalTime? = null
            var newDuration: Duration? = null
            var newRepeatType: RecurringType? = null
            var newDetails: String? = null
            
            while(tokenizer.hasNext()) {
                when(tokenizer.next().tokenValue) {
                    "named" -> {
                        newTitle = tokenizer.nextOrNull()?.tokenValue ?: return
                    }
                    "on" -> {
                        newStartDate = parseDate(tokenizer) ?: let {
                            sourceMessage.channel.sendMessage("Failed to parse date.").queue()
                            return
                        }
                    }
                    "at" -> {
                        newStartTime = parseTime(tokenizer) ?: let {
                            sourceMessage.channel.sendMessage("Failed to parse time.").queue()
                            return
                        }
                    }
                    "lasting" -> {
                        newDuration = parseDuration(tokenizer) ?: let {
                            sourceMessage.channel.sendMessage("Failed to parse duration.").queue()
                            return
                        }
                    }
                    "repeating" -> {
                        newRepeatType = parseRepeatType(tokenizer) ?: let {
                            sourceMessage.channel.sendMessage("Failed to parse repeat type.").queue()
                            return
                        }
                    }
                    "with" -> {
                        newDetails = (checkAndParse(tokenizer, "description") {it.nextOrNull()?.tokenValue} ?: let {
                            sourceMessage.channel.sendMessage("Failed to parse description.").queue()
                            return
                        }).orElse("")
                    }
                }
            }
    
            val needToUpdateRole = newTitle != null
            val needToUpdateEmbed = newTitle != null || newDetails != null || newStartDate != null || newStartTime != null || newDuration != null || newRepeatType != null
    
            synchronized(eventLock) {
                val event = events.firstOrNull {it.uuid == uuid}
                if(event != null) {
                    if(newTitle != null)
                        event.title = newTitle
                    
                    if(newDetails != null)
                        event.details = newDetails
                    
                    if(newStartDate != null && newStartTime != null)
                        event.startingTime = newStartDate.atTime(newStartTime)
                    else if(newStartDate != null)
                        event.startingTime = newStartDate.atTime(event.startingTime.toLocalTime())
                    else if(newStartTime != null)
                        event.startingTime = event.startingTime.toLocalDate().atTime(newStartTime)
                    
                    if(newDuration != null)
                        event.duration = newDuration
                    
                    if(newRepeatType != null)
                        event.repeatType = newRepeatType
            
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
        
        private fun parseDate(tokenizer: Tokenizer): LocalDate? {
            val relativeDate = tokenizer.nextOrNull()?.tokenValue ?: return null
            if(relativeDate.equals("today", true))
                return LocalDate.now()
            else if(relativeDate.equals("tomorrow", true))
                return LocalDate.now().plusDays(1)
            
            val month = Month.values().firstOrNull {it.name.equals(relativeDate, true)} ?: return null
            val day = tokenizer.nextOrNull()?.objValue as? Long ?: return null
            val year = tokenizer.nextOrNull()?.objValue as? Long ?: return null
            
            return try {
                LocalDate.of(year.toInt(), month, day.toInt())
            }
            catch(_: Exception) {
                null
            }
        }
        
        private fun parseTime(tokenizer: Tokenizer): LocalTime? {
            val time = tokenizer.nextOrNull()?.tokenValue ?: return null
            tokenizer.mark()
            val isPM = tokenizer.nextOrNull()?.tokenValue.let {
                when {
                    it.equals("pm", true) -> true
                    it.equals("am", true) -> false
                    else -> {
                        tokenizer.revert()
                        null
                    }
                }
            }
            
            val separatorIndex = time.indexOf(':')
            val hour = if(separatorIndex > 0)
                time.substring(0, separatorIndex).toIntOrNull() ?: return null
            else
                time.toIntOrNull() ?: return null
            val minute = if(separatorIndex > 0)
                time.substring(separatorIndex + 1).toIntOrNull() ?: return null
            else
                0
            
            if(minute !in 0..59)
                return null
    
            return if(isPM == null && hour in 0..23)
                LocalTime.of(hour, minute)
            else if(isPM == true && hour in 1..12)
                LocalTime.of(hour % 12 + 12, minute)
            else if(isPM == false && hour in 1..12)
                LocalTime.of(hour % 12, minute)
            else
                null
        }
        
        private fun parseDuration(tokenizer: Tokenizer): Duration? {
            var duration = Duration.ZERO
            while(tokenizer.hasNext()) {
                tokenizer.mark()
                val num = tokenizer.next().objValue as? Long
                if(num == null) {
                    tokenizer.revert()
                    break
                }
                val unit = tokenizer.nextOrNull()?.tokenValue ?: return null
                duration = if(unit.equals("day", true) || unit.equals("days", true))
                    duration.plusDays(num)
                else if(unit.equals("hour", true) || unit.equals("hours", true))
                    duration.plusHours(num)
                else if(unit.equals("minute", true) || unit.equals("minutes", true))
                    duration.plusMinutes(num)
                else
                    return null
            }
            return if(duration.isZero) null else duration
        }
        
        private fun parseRepeatType(tokenizer: Tokenizer): RecurringType? {
            val text = tokenizer.next().tokenValue
            return RecurringType.values().firstOrNull {it.name.equals(text, true)}
        }
    }
    
    class Time: Command("time", allowedInPrivateChannel = true) {
        override fun helpMessage() = """`${botPrefix}time` __Used for checking the current date and time of the bot__
            |
            |**Usage:** ${botPrefix}time
            |
            |**Examples:**
            |`${botPrefix}time` displays the current date and time for the bot
        """.trimMargin()
        
        override fun invoke(tokenizer: Tokenizer, sourceMessage: Message) {
            sourceMessage.channel.sendMessage("It is currently ${prettyPrintDate(LocalDateTime.now())}").queue()
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
            splitAt2000(message).forEach {
                sourceMessage.channel.sendMessage(it).queue()
            }
        }
    }
}