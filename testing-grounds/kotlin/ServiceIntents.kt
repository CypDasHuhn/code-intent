class UserController(private val userService: UserService) {
    fun find(id: String): User = userService.find(id)

    fun save(value: User) {
        userService.save(value)
    }
}

class ClientHolder {
    fun initialize() {
        val client = HttpClient()
        client.connect()
    }
}

class AnnotatedUserController @Inject constructor(private val annotatedService: UserService) {
    fun find(id: String): User = annotatedService.find(id)
}
