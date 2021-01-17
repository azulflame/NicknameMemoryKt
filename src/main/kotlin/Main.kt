import com.jessecorbett.diskord.api.websocket.model.GatewayIntents
import com.jessecorbett.diskord.dsl.Bot
import com.jessecorbett.diskord.dsl.bot
import com.jessecorbett.diskord.dsl.command
import com.jessecorbett.diskord.dsl.commands
import com.jessecorbett.diskord.util.*
import com.sun.javafx.application.PlatformImpl.startup
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
	if(missingEnvs.isNotEmpty())
	{
		println("The following environment variables are missing:")
		missingEnvs.forEach { println(it)}
		exitProcess(1)
	}

	val prefix = getenv("PREFIX")
	val completeReaction = "✅"
	val currentlyWorkingReaction = "⌛"
	val rejectedReaction = "❌"
	val serversRun: HashSet<String> = HashSet()


	log.info("starting bot")
	bot(getenv("TOKEN"), intents = GatewayIntents.ALL)
	{
		startup()
		{
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
				if(authorId == getenv("OWNERID"))
				{
					react(completeReaction)
					restart()
				}
				else
				{
					react(rejectedReaction)
				}
			}
			command("reload-users")
			{
				if(authorId == getenv("OWNERID"))
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
									logToChannel(
										guildId,
										"${author.mention} caused a full database pull of this server"
									)
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
				}
				else
				{
					react(rejectedReaction)
				}
			}
			command("source")
			{
				reply("You can find the source to this bot here: https://github.com/azulflame/NicknameMemory")
			}
		}
		guildMemberUpdated {
			addOrUpdateUser(it.user.id, it.nickname ?: "", it.roles.joinToString(","), it.guildId)
			logToChannel(it.guildId, "${it.user.mention} updated with nick \"${it.nickname}\" and roles ${it.roles.joinToString(",")}")
			log.info(
				"user ${it.user.id} on ${it.guildId} updated with nickname \"${it.nickname}\" and roles ${
					it.roles.joinToString(
						","
					)
				}"
			)
		}
		userJoinedGuild {
			it.user?.id?.let { userID ->
				getUserInformation(userID, it.guildId)?.let { info ->
					clientStore.guilds[it.guildId].let { guild ->
						guild.changeNickname(userID, info.nick)
						info.roles.split(",").forEach { role ->
							guild.addMemberRole(userID, role)
						}
						log.info("User $userID in server ${it.guildId} given nickname \"${info.nick}\" and roles ${info.roles}")
						logToChannel(it.guildId, "User ${it.user?.mention} in server ${it.guildId} given nickname \"${info.nick}\" and roles ${info.roles}")
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
fun addOrUpdateUser(userId: String, nick: String, roleString: String, serverId: String)
{
	try
	{
		val rowsChanged = DB.connection.update(UserTable)
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
			DB.connection.insert(UserTable) {
				set(it.userID, userId)
				set(it.nickname, nick)
				set(it.roles, roleString)
				set(it.serverID, serverId)
			}
		}
	} catch (e: SQLException)
	{
		log.error("${e.errorCode} encountered while updating user $userId in guild $serverId")
	}
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
 * @param message the message to log
 */
suspend fun Bot.logToChannel(guildId: String, message: String) =
	clientStore.guilds[guildId].getChannels().firstOrNull { x -> x.name == getenv("LOGCHANNEL") }?.id?.let {
		clientStore.channels[it].sendMessage(message)
	}