package com.odiousapps.kat.db.model

data class DbRecipePreview (
    val id: Long,
    val name: String,
    val description: String = "",
    val thumbImageUrl: String = "",
    val starred: Boolean,
) {
    companion object{
        const val DBFIELDS = "id, name, description, thumbImageUrl, starred"
    }

    override fun equals(other: Any?): Boolean {
        return if (other == null || other !is DbRecipePreview) false else id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}
