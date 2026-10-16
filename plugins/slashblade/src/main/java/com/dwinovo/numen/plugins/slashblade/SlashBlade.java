    private static Art doArt(LivingEntity entity, ISlashBladeState state, String type) {
        if (state.isBroken()) {
            return new Art(false, artOf(state), "the blade is broken; repair it first");
        }
        if (state.isSealed()) {
            return new Art(false, artOf(state), "the blade is sealed");
        }
        String key = shortName(state.getSlashArtsKey());
        BiConsumer<LivingEntity, Boolean> action = ARTS.get(key);
        if (action == null) {
            return new Art(false, artOf(state), "this blade's slash art is not supported");
        }
        boolean just = "just".equals(normalize(type));
        action.accept(entity, just);
        entity.swing(InteractionHand.MAIN_HAND);
        return new Art(true, artOf(state), "");
    }
