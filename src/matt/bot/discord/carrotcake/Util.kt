package matt.bot.discord.carrotcake

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime

fun countMentions(message: Message) = message.mentionedChannels.size + message.mentionedRoles.size + message.mentionedUsers.size + if(message.mentionsEveryone()) 1 else 0

fun isServerAdmin(member: Member) = member.isOwner || member.roles.intersect(joinedGuilds[member.guild]!!.serverAdminRoles).isNotEmpty() || member.hasPermission(Permission.ADMINISTRATOR) || member.hasPermission(Permission.MANAGE_SERVER)

fun canManageEvents(member: Member) = joinedGuilds[member.guild]!!.eventManagerRole in member.roles || isServerAdmin(member)

fun reloadBot(bot: JDA)
{
    shutdownMode = ExitMode.RELOAD
    bot.shutdown()
}

fun shutdownBot(bot: JDA)
{
    shutdownMode = ExitMode.SHUTDOWN
    bot.shutdown()
}

/**
 * All items matching the filter are put into the first list. All items not matching the filter are put into the second list
 */
inline fun <reified T, reified U> Collection<T>.splitAndMap(filter: (T) -> Boolean, mapper: (T) -> (U)): Pair<List<U>, List<U>>
{
    val l1 = mutableListOf<U>()
    val l2 = mutableListOf<U>()
    forEach {
        if(filter(it))
            l1.add(mapper(it))
        else
            l2.add(mapper(it))
    }
    return Pair(l1, l2)
}

fun JSONObject.getStringOrNull(key: String) = if(isNull(key)) null else getString(key)

inline fun <reified T> retryOrReturn(amt: Int, defaultValue: T, provider: () -> T): T {
    return try {
        retry(amt, provider)
    }
    catch(_: Throwable) {
        defaultValue
    }
}

inline fun <reified T> retry(amt: Int, provider: () -> T): T
{
    if(amt <= 0)
    {
        while(true)
        {
            try
            {
                return provider()
            }
            catch(_: Throwable) {}
        }
    }
    else
    {
        var lastThrowable = Throwable() // unused throwable that is used to prevent the compiler from complaining about an impossible lack of initialization before use
        for(i in 0 until amt)
        {
            try
            {
                return provider()
            }
            catch(t: Throwable)
            {
                lastThrowable = t
            }
        }
        throw lastThrowable
    }
}

fun String.containsSparse(text: String): Boolean
{
    if(text.length > length)
        return false
    var index = 0
    for(c in text)
    {
        index = indexOf(c, index) + 1
        if(index <= 0)
            return false
    }
    return true
}

fun prettyPrintDate(localDateTime: LocalDateTime): String {
    return "${localDateTime.month.name.toLowerCase().capitalize()} ${localDateTime.dayOfMonth}, ${localDateTime.year}, at ${localDateTime.hour}:${localDateTime.minute.toString().padStart(2, '0')} CDT"
}

fun prettyPrintDuration(duration: Duration): String {
    val hours = duration.toHours()
    val minutes = duration.toMinutes() - 60 * hours
    
    val hourStr = when {
        hours > 1 -> "$hours hours"
        hours == 1L -> "1 hour"
        else -> ""
    }
    
    val minuteStr = when {
        minutes > 1 -> "$minutes minutes"
        minutes == 1L -> "1 minute"
        else -> ""
    }
    
    return if(hourStr.isNotBlank() && minuteStr.isNotBlank())
        "$hourStr and $minuteStr"
    else if(hourStr.isNotBlank())
        hourStr
    else if(minuteStr.isNotBlank())
        minuteStr
    else
        "0 minutes"
}

fun splitAt2000(text: String): List<String>
{
    if(text.length <= 2000)
        return listOf(text)
    val splitIndex = text.lastIndexOf('\n', 2000).takeIf {it >= 0} ?: text.lastIndexOf(' ', 2000)
    return if(splitIndex < 0)
        listOf(text.substring(0, 2000)) + splitAt2000(text.substring(2000))
    else
        listOf(text.substring(0, splitIndex)) + splitAt2000(text.substring(splitIndex))
}