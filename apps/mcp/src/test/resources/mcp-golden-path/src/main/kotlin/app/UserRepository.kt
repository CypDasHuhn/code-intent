package app

class UserRepository {
    fun findUser(id: String): String = "user-" + id
}
