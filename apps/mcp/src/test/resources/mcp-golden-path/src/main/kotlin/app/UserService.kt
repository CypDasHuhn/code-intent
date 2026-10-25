package app

import app.TextTools.renderLabel

class UserService(
    private val repository: UserRepository,
) {
    fun loadUser(id: String): String {
        val normalized = id.renderLabel()
        return repository.findUser(normalized)
    }
}
