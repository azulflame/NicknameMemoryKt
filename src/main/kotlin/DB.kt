import org.ktorm.database.Database

object DB
{
	var connection = Database.connect(
		System.getenv("DBURL"),
		"com.mysql.cj.jdbc.Driver",
		System.getenv("DBUSER"),
		System.getenv("DBPASS")
	)
}