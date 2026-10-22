import java.io.File;
import java.nio.file.Path;
import net.minecraftforge.srgutils.IMappingFile;

/** Calls the exact upstream DOWNLOAD_MOJMAPS --sanitize conversion on verified cached input. */
public final class ConvertVendorMappings {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) throw new IllegalArgumentException("input output format");
        IMappingFile.load(new File(arguments[0])).write(Path.of(arguments[1]), IMappingFile.Format.valueOf(arguments[2]), false);
    }
}
