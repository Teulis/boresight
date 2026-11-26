import com.boresight.Agg;

import java.util.List;

/**
 * The physical-channel parser, against text captured from a real device.
 *
 * This is worth testing on the desktop because it fails quietly: a parser that returns
 * an empty list looks exactly like a phone that is not aggregating, and the screen
 * would say "single carrier" with total confidence. The sample below is the real
 * output from a Galaxy S25 Ultra on T-Mobile, forced to LTE, aggregating three
 * carriers — checked against service mode at the same moment.
 */
public class AggTest {

    private static int failed = 0;

    public static void main(String[] args) {
        threeCarriers();
        emptySlotDoesNotWin();
        contextIdsDoNotConfuseTheBrackets();
        unsetNumericsReadAsUnknown();
        idleSlotStubIsNotACarrier();
        junkIsEmptyNotCrash();

        if (failed > 0) {
            System.out.println("\nAggTest: " + failed + " failure(s)");
            System.exit(1);
        }
        System.out.println("AggTest ok");
    }

    // The real thing: two slots, one idle and one carrying 3CC.
    private static final String REAL =
        "  mPhysicalChannelConfigs=[{mConnectionStatus=PrimaryServing,mCellBandwidthDownlinkKhz=0,"
      + "mCellBandwidthUplinkKhz=0,mNetworkType=UNKNOWN,mFrequencyRange=UNKNOWN,"
      + "mDownlinkChannelNumber=0,mUplinkChannelNumber=0,mContextIds=[],mPhysicalCellId=0,"
      + "mBand=0,mDownlinkFrequency=0,mUplinkFrequency=0}]\n"
      + "  mPhysicalChannelConfigs=[{mConnectionStatus=PrimaryServing,"
      + "mCellBandwidthDownlinkKhz=15000,mCellBandwidthUplinkKhz=0,mNetworkType=LTE_CA,"
      + "mFrequencyRange=LOW,mDownlinkChannelNumber=0,mUplinkChannelNumber=0,"
      + "mContextIds=[8, 9],mPhysicalCellId=75,mBand=2,mDownlinkFrequency=0,"
      + "mUplinkFrequency=0}, {mConnectionStatus=SecondaryServing,"
      + "mCellBandwidthDownlinkKhz=10000,mCellBandwidthUplinkKhz=0,mNetworkType=LTE_CA,"
      + "mFrequencyRange=LOW,mDownlinkChannelNumber=0,mUplinkChannelNumber=0,"
      + "mContextIds=[8, 9],mPhysicalCellId=210,mBand=66,mDownlinkFrequency=0,"
      + "mUplinkFrequency=0}, {mConnectionStatus=SecondaryServing,"
      + "mCellBandwidthDownlinkKhz=15000,mCellBandwidthUplinkKhz=0,mNetworkType=LTE_CA,"
      + "mFrequencyRange=LOW,mDownlinkChannelNumber=0,mUplinkChannelNumber=0,"
      + "mContextIds=[8, 9],mPhysicalCellId=150,mBand=66,mDownlinkFrequency=0,"
      + "mUplinkFrequency=0}]\n";

    private static void threeCarriers() {
        System.out.println("\n-- the real capture --");
        List<Agg.Cc> cc = Agg.parsePhysicalChannels(REAL);
        ok("three carriers found", cc.size() == 3);
        if (cc.size() != 3) return;

        ok("primary first", cc.get(0).primary);
        ok("secondaries after", !cc.get(1).primary && !cc.get(2).primary);

        // Matches service mode: PCC B2 15 MHz, SCC B66 10 MHz PCI 210, SCC B66 15 PCI 150.
        ok("pcc is band 2", cc.get(0).band == 2);
        ok("pcc is 15 MHz", cc.get(0).bandwidthKhz == 15000);
        ok("pcc pci 75", cc.get(0).pci == 75);
        ok("scc1 band 66", cc.get(1).band == 66);
        ok("scc1 10 MHz", cc.get(1).bandwidthKhz == 10000);
        ok("scc1 pci 210", cc.get(1).pci == 210);
        ok("scc2 15 MHz", cc.get(2).bandwidthKhz == 15000);
        ok("scc2 pci 150", cc.get(2).pci == 150);
        ok("labelled as LTE bands", "B2".equals(cc.get(0).bandLabel()));
        ok("bandwidth reads in MHz", "15 MHz".equals(cc.get(0).bwLabel()));
    }

    private static void emptySlotDoesNotWin() {
        System.out.println("\n-- the idle slot must not overwrite the busy one --");
        // Same two lists, the empty one last. Order must not decide the answer.
        String reversed =
            "mPhysicalChannelConfigs=[{mConnectionStatus=PrimaryServing,"
          + "mCellBandwidthDownlinkKhz=15000,mNetworkType=LTE_CA,mContextIds=[8, 9],"
          + "mPhysicalCellId=75,mBand=2}, {mConnectionStatus=SecondaryServing,"
          + "mCellBandwidthDownlinkKhz=10000,mNetworkType=LTE_CA,mContextIds=[8, 9],"
          + "mPhysicalCellId=210,mBand=66}]\n"
          + "mPhysicalChannelConfigs=[]\n";
        ok("richest list wins", Agg.parsePhysicalChannels(reversed).size() == 2);
        ok("a wholly empty dump is empty",
                Agg.parsePhysicalChannels("mPhysicalChannelConfigs=[]").isEmpty());
    }

    private static void contextIdsDoNotConfuseTheBrackets() {
        System.out.println("\n-- mContextIds is itself a bracketed list --");
        // The reason brackets are counted instead of regex-matched. A lazy match would
        // stop at the ] of mContextIds and find one truncated entry.
        String s = "mPhysicalChannelConfigs=[{mConnectionStatus=PrimaryServing,"
                 + "mContextIds=[1, 2, 3],mBand=71,mCellBandwidthDownlinkKhz=20000,"
                 + "mPhysicalCellId=12}]";
        List<Agg.Cc> cc = Agg.parsePhysicalChannels(s);
        ok("one carrier, not a truncation", cc.size() == 1);
        ok("band survived the inner brackets", cc.size() == 1 && cc.get(0).band == 71);
    }

    private static void unsetNumericsReadAsUnknown() {
        System.out.println("\n-- zero means unset on fields where zero is not an answer --");
        // Band is known, so this is a real carrier even though everything else is zero.
        // That is the 5G standalone case on a Galaxy S25.
        String s = "mPhysicalChannelConfigs=[{mConnectionStatus=PrimaryServing,"
                 + "mCellBandwidthDownlinkKhz=0,mNetworkType=NR,mDownlinkChannelNumber=0,"
                 + "mContextIds=[8, 9],mPhysicalCellId=0,mBand=41}]";
        List<Agg.Cc> cc = Agg.parsePhysicalChannels(s);
        ok("still one carrier", cc.size() == 1);
        if (cc.isEmpty()) return;
        ok("bandwidth 0 is unknown", cc.get(0).bandwidthKhz == -1);
        ok("unknown bandwidth renders as a dash", "—".equals(cc.get(0).bwLabel()));
        ok("nr bands are labelled with n", "n41".equals(cc.get(0).bandLabel()));
        // PCI 0 is a legal cell id, so it is NOT rewritten to unknown.
        ok("pci 0 is kept, because 0 is a real pci", cc.get(0).pci == 0);
    }

    private static void idleSlotStubIsNotACarrier() {
        System.out.println("\n-- an idle slot's placeholder is not a carrier --");
        // The real failure this caused: the idle slot's one empty entry beat the
        // aggregating slot's list under a longest-list rule, and the screen showed
        // "single carrier, pci 0" while the phone was running 3CC.
        String stub = "mPhysicalChannelConfigs=[{mConnectionStatus=PrimaryServing,"
                    + "mCellBandwidthDownlinkKhz=0,mCellBandwidthUplinkKhz=0,"
                    + "mNetworkType=UNKNOWN,mFrequencyRange=UNKNOWN,"
                    + "mDownlinkChannelNumber=0,mUplinkChannelNumber=0,mContextIds=[],"
                    + "mPhysicalCellId=0,mBand=0,mDownlinkFrequency=0,mUplinkFrequency=0}]";
        ok("a lone stub yields nothing", Agg.parsePhysicalChannels(stub).isEmpty());

        // Stub slot first, real slot second: the real one must still win.
        String both = stub + "\n" + REAL_ONE;
        List<Agg.Cc> cc = Agg.parsePhysicalChannels(both);
        ok("the real slot wins over the stub", cc.size() == 1);
        ok("and it is the real carrier", !cc.isEmpty() && cc.get(0).band == 66);
    }

    private static final String REAL_ONE =
        "mPhysicalChannelConfigs=[{mConnectionStatus=PrimaryServing,"
      + "mCellBandwidthDownlinkKhz=15000,mNetworkType=LTE_CA,mContextIds=[8, 9],"
      + "mPhysicalCellId=150,mBand=66}]";

    private static void junkIsEmptyNotCrash() {
        System.out.println("\n-- malformed input --");
        ok("null", Agg.parsePhysicalChannels(null).isEmpty());
        ok("empty string", Agg.parsePhysicalChannels("").isEmpty());
        ok("no key", Agg.parsePhysicalChannels("nothing to see here").isEmpty());
        ok("unterminated list",
                Agg.parsePhysicalChannels("mPhysicalChannelConfigs=[{mConnectionStatus=Prim")
                        .isEmpty());
        ok("brace without close",
                Agg.parsePhysicalChannels("mPhysicalChannelConfigs=[{foo]").isEmpty());
    }

    private static void ok(String what, boolean pass) {
        System.out.printf("  %-4s %s%n", pass ? "ok" : "FAIL", what);
        if (!pass) failed++;
    }
}
