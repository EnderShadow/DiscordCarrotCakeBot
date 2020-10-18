package matt.bot.discord.carrotcake

import net.dv8tion.jda.api.entities.*

class GuildInfo(val guild: Guild, serverAdminRoles: List<Role> = emptyList(), var eventManagerRole: Role? = null, var eventChannel: TextChannel? = null)
{
    val serverAdminRoles = serverAdminRoles.toMutableList()
}