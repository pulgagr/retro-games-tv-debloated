// Tests the app's SMB client from a computer: java tools/SmbTest.java <host> <share> <path>
// (run via tools/smbtest.sh, which compiles it together with app/src/.../Smb.java)
import com.example.retro.Smb;

public class SmbTest {
    public static void main(String[] a) throws Exception {
        long t = System.nanoTime();
        Smb smb = new Smb(a[0], a[1]);
        System.out.printf("login ok (%d ms)%n", (System.nanoTime() - t) / 1000000);
        for (int i = 2; i < a.length; i++) {
            t = System.nanoTime();
            byte[] d = smb.read(a[i]);
            long ms = (System.nanoTime() - t) / 1000000;
            System.out.printf("%s: %d bytes in %d ms, starts %02x %02x%n", a[i], d.length, ms, d[0], d[1]);
        }
        smb.close();
    }
}
