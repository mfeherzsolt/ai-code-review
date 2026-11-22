package hu.demo.countdown;

import java.util.Date;

public class Countdown {

    public static void main(String[] args) throws Exception {
        int seconds = Integer.parseInt(args[0]);
        Date start = new Date();
        while (true) {
            Date now = new Date();
            long diff = (now.getTime() - start.getTime()) / 1000;
            long left = seconds - diff;
            if (left <= 0) {
                System.out.println("Done!");
                break;
            }
            long h = left / 3600;
            long m = (left % 3600) / 60;
            long s = left % 60;
            System.out.println(h + ":" + m + ":" + s);
            try {
                Thread.sleep(1000);
            } catch (Exception e) {
            }
        }
    }
}
