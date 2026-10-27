package probe;

import com.zurrtum.create.client.catnip.render.SuperByteBuffer;

/** Runs without starting Minecraft or creating a graphics context. */
public class EmptyBufferProbe {
    public static void main(String[] args) {
        SuperByteBuffer empty = SuperByteBuffer.empty();
        if (empty.keepAlive() != empty) throw new AssertionError("Empty buffer identity changed");
        if (!empty.keepAlive().extractRenderState().isEmpty())
            throw new AssertionError("Empty buffer produced geometry");
        if (!empty.isEmpty()) throw new AssertionError("Empty singleton stopped being empty");
        System.out.println("EMPTY_BUFFER_PROBE_RESULT failures=0 checks=3");
    }
}
