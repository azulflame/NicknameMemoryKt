import org.ktorm.schema.Table
import org.ktorm.schema.text
import org.ktorm.schema.varchar

/*
 * Created by Todd on 1/15/2021.
 */
object UserTable : Table<Nothing>("users")
{
	val userID = varchar("userID")
	val roles = text("roles")
	val nickname = varchar("nickname")
	val serverID = varchar("serverID")
}