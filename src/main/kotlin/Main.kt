import com.jessecorbett.diskord.api.rest.client.ChannelClient
import com.jessecorbett.diskord.api.rest.client.GuildClient
import com.jessecorbett.diskord.api.websocket.model.GatewayIntents
import com.jessecorbett.diskord.dsl.*
import com.jessecorbett.diskord.util.authorId
import com.jessecorbett.diskord.util.changeNickname
import com.jessecorbett.diskord.util.sendMessage
import com.jessecorbett.diskord.util.words
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.ktorm.dsl.*
import org.slf4j.Logger
import java.lang.System.getenv
import java.sql.SQLException
import java.util.*
import kotlin.system.exitProcess

/*
 * Created by Todd on 1/14/2021.
 */

val log: Logger = KotlinLogging.logger { }


suspend fun main()
{
	log.info("checking envvars")
	val missingEnvs = listOf("TOKEN", "OWNERID", "DBURL", "DBPASS", "DBUSER", "LOGCHANNEL", "PREFIX").filter { x ->
		!getenv().keys.contains(x)
	}
	log.info("finished checking envvars")
	if (missingEnvs.isNotEmpty())
	{
		println("The following environment variables are missing:")
		missingEnvs.forEach { println(it) }
		exitProcess(1)
	}
	log.info("checking database connection")

	val prefix = getenv("PREFIX")
	val completeReaction = "✅"
	val currentlyWorkingReaction = "⌛"
	val rejectedReaction = "❌"
	val serversRun: HashSet<String> = HashSet()


	log.info("starting bot")
	bot(getenv("TOKEN"), intents = GatewayIntents.ALL)
	{
		started {
			log.info("bot startup reached")
		}
		commands(prefix)
		{
			command("shutdown")
			{
				if (authorId == getenv("OWNERID"))
				{
					react(completeReaction)
					shutdown()
				} else
				{
					react(rejectedReaction)
				}
			}
			command("restart")
			{
				if (authorId == getenv("OWNERID"))
				{
					react(completeReaction)
					restart()
				} else
				{
					react(rejectedReaction)
				}
			}
			command("reload-users")
			{
				if (authorId == getenv("OWNERID"))
				{
					if (words.size == 2 && words[1] == "--confirm")
					{
						guildId?.let { guildId ->
							if (!serversRun.contains(guildId))
							{
								serversRun.add(guildId)
								GlobalScope.launch {
									log.info("starting user pull for server $guildId")
									react(currentlyWorkingReaction)
									clientStore.guilds[guildId].getMembers().forEach { member ->
										member.user?.let { user ->
											member.nickname?.let { nick ->
												addOrUpdateUser(
													user.id,
													nick,
													member.roleIds.joinToString(","),
													guildId
												)
											}
										}
									}
									channel.removeMessageReaction(id, currentlyWorkingReaction)
									react(completeReaction)
									log.info("finished user pull for server $guildId")
								}
							} else
							{
								reply("This command has already been run since the bot started")
								react(rejectedReaction)
							}
						}
					} else if (words.drop(1).isEmpty())
					{
						reply("You need to run that command with --confirm")
					} else
					{
						react(rejectedReaction)
					}
				} else
				{
					react(rejectedReaction)
				}
			}
			command("source")
			{
				reply("You can find the source to this bot here: https://github.com/azulflame/NicknameMemoryKt")
			}
		}
		guildMemberUpdated {
			val emptyUpdate =
				it.nickname.isNullOrBlank() && it.roles.isEmpty() // is this an empty update, possibly removed from guild
			// if it is not an empty update, short circuit the user check
			if (!emptyUpdate || clientStore.guilds[it.guildId].hasUser(it.user.id))
			{
				addOrUpdateUser(it.user.id, it.nickname ?: "", it.roles.joinToString(","), it.guildId)
				log.info(
					"user ${it.user.id} on ${it.guildId} updated with nickname \"${it.nickname}\" and roles ${
						it.roles.joinToString(
							","
						)
					}"
				)
			}
		}
		userJoinedGuild {
			it.user?.let { user ->
				getUserInformation(user.id, it.guildId)?.let { info ->
					clientStore.guilds[it.guildId].let { guild ->
						var embedtext = ""
						if (info.nick.isNotBlank())
						{
							log.info("changing nickname")
							guild.changeNickname(user.id, info.nick)
							embedtext += "Nick updated to \"${info.nick}\"\n"
						}
						if (info.roles.isNotBlank())
						{
							embedtext += "Roles added:\n"

							info.roles.split(",").forEach { role ->
								log.info("Adding role $role")
								guild.addMemberRole(user.id, role)
								embedtext += "$role <@&${role}>\n"
							}
						}
						log.info("User ${user.id} in server ${it.guildId} given nickname \"${info.nick}\" and roles ${info.roles}")
						getLogChannel(it.guildId)?.let { channel ->
							channel.sendMessage("",
								embed {
									title = "${user.username}#${user.discriminator} rejoined the server"
									description = embedtext
								}
							)
						}
					}
				}
			}
		}
	}
}

/**
 * Add or update user in the database
 *
 * @param userId ID of the user
 * @param nick the new nickname of the user
 * @param roleString a comma seperated string of the role IDs the user has
 * @param serverId the ID of the server that the user was modified in
 */
fun addOrUpdateUser(userId: String, nick: String, roleString: String, serverId: String): Int
{
	try
	{
		var rowsChanged = DB.connection.update(UserTable)
		{
			set(it.nickname, nick)
			set(it.roles, roleString)
			where {
				it.serverID eq serverId
				it.userID eq userId
			}
		}
		if (rowsChanged == 0)
		{
			// insert command
			rowsChanged = DB.connection.insert(UserTable) {
				set(it.userID, userId)
				set(it.nickname, nick)
				set(it.roles, roleString)
				set(it.serverID, serverId)
			}
		}
		return rowsChanged
	} catch (e: SQLException)
	{
		log.error("${e.errorCode} encountered while updating user $userId in guild $serverId")
	}
	return 0
}

/**
 * Get the user information if it exists. Null if it does not.
 *
 * @param userID the ID of a user to fetch
 * @param serverID the ID of the server the user exists in
 * @return a SimpleMember of the user's information
 */
fun getUserInformation(userID: String, serverID: String): SimpleMember?
{
	var x: SimpleMember? = null
	try
	{
		DB.connection.from(UserTable)
			.select(UserTable.nickname, UserTable.roles)
			.where {
				(UserTable.userID eq userID) and
						(UserTable.serverID eq serverID)
			}.forEach {
				it[UserTable.nickname]?.let { nickname ->
					it[UserTable.roles]?.let { roles ->
						x = SimpleMember(userID, nickname, roles, serverID)
					}
				}
			}
	} catch (e: SQLException)
	{
		log.error("${e.errorCode} encountered while fetching user $userID in guild $serverID")
	}
	return x
}

/**
 * Log to provided message to the channel in the settings
 *
 * @param guildId the server to log the message to
 */
suspend fun Bot.getLogChannel(guildId: String): ChannelClient?
{
	clientStore.guilds[guildId].getChannels()
		.firstOrNull { x -> x.name == getenv("LOGCHANNEL") }?.id?.let { return clientStore.channels[it] }
	return null
}

/**
 * Returns if the user is in the guild of the GuildClient
 *
 * @param userID userID of
 * @return true if the user is in the guild. false if the user is not, or any error occurs
 */
suspend fun GuildClient.hasUser(userID: String): Boolean
{
	return kotlin.runCatching { this.getMember(userID) }.isSuccess
}