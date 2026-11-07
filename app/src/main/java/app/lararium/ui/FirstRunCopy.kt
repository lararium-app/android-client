package app.lararium.ui

/** First-run copy that explains what a hearth is and where to learn more. */
object FirstRunCopy {
    const val INSTALL_GUIDE_URL =
        "https://github.com/lararium-app/lararium/blob/main/docs/INSTALL-GUIDE.md"

    const val SETUP_LINK_LABEL = "How to set up your hearth"

    /** Returns a short plain-language definition of a hearth, or null when already paired. */
    fun hearthExplainer(unpaired: Boolean): String? {
        if (!unpaired) return null
        return "A hearth is your own computer at home that runs Lararium's hearthd server " +
            "and answers only to you. Pairing connects this phone to it."
    }
}
