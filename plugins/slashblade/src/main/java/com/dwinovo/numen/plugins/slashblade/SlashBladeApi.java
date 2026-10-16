    /** 放一次剑技(SA)。 */
    @Fn("Release the slash art (SA) of the slash blade in your main hand, through SlashBlade's own pipeline.")
    @Example("slashblade.blade.art()")
    @Example("slashblade.blade.art(\"just\")")
    @Note("It releases the slash art attached to the blade you hold (see held().sa), so it adapts to whichever "
            + "blade you carry.")
    @Note("\"normal\" is the usual release, \"just\" the just-timed release. Leave type out for \"normal\".")
    @Note("The full move — its animation, effects and combo — is pre-programmed server-side. Call art() and the "
            + "whole art plays; do NOT try to script the swings yourself.")
    @Note("It does not aim for you: face the target first. When it fails, read the reason.")
    @SeeAlso({"slashblade.blade.held", "slashblade.blade.swing", "numen.fight.attack"})
    public static Art art(ServerCall call, ArtSpec args) {
        NumenPlayer her = call.her();
        ItemStack blade = her.getMainHandItem();
        if (!SlashBlade.isSlashBlade(blade)) {
            return new Art(false, "", "no slash blade in your main hand");
        }
        String type = args == null || args.type() == null ? "normal" : args.type().toLowerCase(Locale.ROOT);
        if (!type.equals("normal") && !type.equals("just")) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT,
                    "type must be \"normal\" or \"just\"", null);
        }
        SlashBlade.Art done = SlashBlade.art(her, blade, type);
        return new Art(done.executed(), done.sa(), done.reason());
    }
