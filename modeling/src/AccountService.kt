package preferences.modeling

import preferences.modeling.types.Password
import preferences.modeling.types.UserId

class AccountService(
    private val users: UserRepository,
    private val passwords: PasswordHasher,
) {
    /** Returns the registered user. */
    suspend fun register(username: UserId, password: Password): UserId {
        if (!users.addUser(username, passwords.hash(password))) throw ChatException(ChatError.UsernameTaken)
        return username
    }

    /** Returns the signed-in user. An unknown user and a wrong password fail the same way. */
    suspend fun login(username: UserId, password: Password): UserId {
        val hash = users.passwordHash(username)
        if (hash == null || !passwords.verify(password, hash)) throw ChatException(ChatError.InvalidCredentials)
        return username
    }
}

/** Signs in without a network: the [ChatClient] acts directly on [chat]. */
class LocalChatGateway(
    private val accounts: AccountService,
    private val chat: ChatService,
) : ChatGateway {
    override suspend fun register(username: UserId, password: Password): ChatClient =
        chat.actingAs(accounts.register(username, password))

    override suspend fun login(username: UserId, password: Password): ChatClient =
        chat.actingAs(accounts.login(username, password))
}
