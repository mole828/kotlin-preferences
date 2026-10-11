package preferences.modeling

interface Database {
    // Normal return commits; an escaping exception rolls back.
    suspend fun <T> transaction(block: suspend TransactionScope.() -> T): T
}

interface TransactionScope {
    val memberships: MembershipService
    val messages: ChatRoomRepository
}
