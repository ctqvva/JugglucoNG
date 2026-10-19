package tk.glucodata;

import java.security.SecureRandom;

public class Random {
   private static final SecureRandom random=new SecureRandom();
   static public void fillbytes(byte[] bytes) {
      random.nextBytes(bytes);
      }
   };
