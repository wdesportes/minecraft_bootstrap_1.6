package net.minecraft.bootstrap;

public class FatalBootstrapError extends RuntimeException {
    public FatalBootstrapError(final String reason) {
        super(reason);
    }
}