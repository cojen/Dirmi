/*
 *  Copyright 2022 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi;

import java.net.ServerSocket;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class LargeRemoteTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(LargeRemoteTest.class.getName());
    }

    @Test
    public void basic() throws Exception {
        var env = Environment.create();
        env.export("main", new R1Server());
        var ss = new ServerSocket(0);
        env.acceptAll(ss);

        var session = env.connect(R1.class, "main", "localhost", ss.getLocalPort());
        R1 r1 = session.root();

        for (int i=0; i<300; i++) {
            Object result = R1.class.getMethod("a" + i).invoke(r1);
            assertEquals(i, result);
        }

        env.close();
    }

    @RemoteFailure(declared=false)
    public static interface R1 extends Remote {
        public int a0();
        public int a1();
        public int a2();
        public int a3();
        public int a4();
        public int a5();
        public int a6();
        public int a7();
        public int a8();
        public int a9();
        public int a10();
        public int a11();
        public int a12();
        public int a13();
        public int a14();
        public int a15();
        public int a16();
        public int a17();
        public int a18();
        public int a19();
        public int a20();
        public int a21();
        public int a22();
        public int a23();
        public int a24();
        public int a25();
        public int a26();
        public int a27();
        public int a28();
        public int a29();
        public int a30();
        public int a31();
        public int a32();
        public int a33();
        public int a34();
        public int a35();
        public int a36();
        public int a37();
        public int a38();
        public int a39();
        public int a40();
        public int a41();
        public int a42();
        public int a43();
        public int a44();
        public int a45();
        public int a46();
        public int a47();
        public int a48();
        public int a49();
        public int a50();
        public int a51();
        public int a52();
        public int a53();
        public int a54();
        public int a55();
        public int a56();
        public int a57();
        public int a58();
        public int a59();
        public int a60();
        public int a61();
        public int a62();
        public int a63();
        public int a64();
        public int a65();
        public int a66();
        public int a67();
        public int a68();
        public int a69();
        public int a70();
        public int a71();
        public int a72();
        public int a73();
        public int a74();
        public int a75();
        public int a76();
        public int a77();
        public int a78();
        public int a79();
        public int a80();
        public int a81();
        public int a82();
        public int a83();
        public int a84();
        public int a85();
        public int a86();
        public int a87();
        public int a88();
        public int a89();
        public int a90();
        public int a91();
        public int a92();
        public int a93();
        public int a94();
        public int a95();
        public int a96();
        public int a97();
        public int a98();
        public int a99();
        public int a100();
        public int a101();
        public int a102();
        public int a103();
        public int a104();
        public int a105();
        public int a106();
        public int a107();
        public int a108();
        public int a109();
        public int a110();
        public int a111();
        public int a112();
        public int a113();
        public int a114();
        public int a115();
        public int a116();
        public int a117();
        public int a118();
        public int a119();
        public int a120();
        public int a121();
        public int a122();
        public int a123();
        public int a124();
        public int a125();
        public int a126();
        public int a127();
        public int a128();
        public int a129();
        public int a130();
        public int a131();
        public int a132();
        public int a133();
        public int a134();
        public int a135();
        public int a136();
        public int a137();
        public int a138();
        public int a139();
        public int a140();
        public int a141();
        public int a142();
        public int a143();
        public int a144();
        public int a145();
        public int a146();
        public int a147();
        public int a148();
        public int a149();
        public int a150();
        public int a151();
        public int a152();
        public int a153();
        public int a154();
        public int a155();
        public int a156();
        public int a157();
        public int a158();
        public int a159();
        public int a160();
        public int a161();
        public int a162();
        public int a163();
        public int a164();
        public int a165();
        public int a166();
        public int a167();
        public int a168();
        public int a169();
        public int a170();
        public int a171();
        public int a172();
        public int a173();
        public int a174();
        public int a175();
        public int a176();
        public int a177();
        public int a178();
        public int a179();
        public int a180();
        public int a181();
        public int a182();
        public int a183();
        public int a184();
        public int a185();
        public int a186();
        public int a187();
        public int a188();
        public int a189();
        public int a190();
        public int a191();
        public int a192();
        public int a193();
        public int a194();
        public int a195();
        public int a196();
        public int a197();
        public int a198();
        public int a199();
        public int a200();
        public int a201();
        public int a202();
        public int a203();
        public int a204();
        public int a205();
        public int a206();
        public int a207();
        public int a208();
        public int a209();
        public int a210();
        public int a211();
        public int a212();
        public int a213();
        public int a214();
        public int a215();
        public int a216();
        public int a217();
        public int a218();
        public int a219();
        public int a220();
        public int a221();
        public int a222();
        public int a223();
        public int a224();
        public int a225();
        public int a226();
        public int a227();
        public int a228();
        public int a229();
        public int a230();
        public int a231();
        public int a232();
        public int a233();
        public int a234();
        public int a235();
        public int a236();
        public int a237();
        public int a238();
        public int a239();
        public int a240();
        public int a241();
        public int a242();
        public int a243();
        public int a244();
        public int a245();
        public int a246();
        public int a247();
        public int a248();
        public int a249();
        public int a250();
        public int a251();
        public int a252();
        public int a253();
        public int a254();
        public int a255();
        public int a256();
        public int a257();
        public int a258();
        public int a259();
        public int a260();
        public int a261();
        public int a262();
        public int a263();
        public int a264();
        public int a265();
        public int a266();
        public int a267();
        public int a268();
        public int a269();
        public int a270();
        public int a271();
        public int a272();
        public int a273();
        public int a274();
        public int a275();
        public int a276();
        public int a277();
        public int a278();
        public int a279();
        public int a280();
        public int a281();
        public int a282();
        public int a283();
        public int a284();
        public int a285();
        public int a286();
        public int a287();
        public int a288();
        public int a289();
        public int a290();
        public int a291();
        public int a292();
        public int a293();
        public int a294();
        public int a295();
        public int a296();
        public int a297();
        public int a298();
        public int a299();
    }

    public static class R1Server implements R1 {
        public int a0() { return 0; }
        public int a1() { return 1; }
        public int a2() { return 2; }
        public int a3() { return 3; }
        public int a4() { return 4; }
        public int a5() { return 5; }
        public int a6() { return 6; }
        public int a7() { return 7; }
        public int a8() { return 8; }
        public int a9() { return 9; }
        public int a10() { return 10; }
        public int a11() { return 11; }
        public int a12() { return 12; }
        public int a13() { return 13; }
        public int a14() { return 14; }
        public int a15() { return 15; }
        public int a16() { return 16; }
        public int a17() { return 17; }
        public int a18() { return 18; }
        public int a19() { return 19; }
        public int a20() { return 20; }
        public int a21() { return 21; }
        public int a22() { return 22; }
        public int a23() { return 23; }
        public int a24() { return 24; }
        public int a25() { return 25; }
        public int a26() { return 26; }
        public int a27() { return 27; }
        public int a28() { return 28; }
        public int a29() { return 29; }
        public int a30() { return 30; }
        public int a31() { return 31; }
        public int a32() { return 32; }
        public int a33() { return 33; }
        public int a34() { return 34; }
        public int a35() { return 35; }
        public int a36() { return 36; }
        public int a37() { return 37; }
        public int a38() { return 38; }
        public int a39() { return 39; }
        public int a40() { return 40; }
        public int a41() { return 41; }
        public int a42() { return 42; }
        public int a43() { return 43; }
        public int a44() { return 44; }
        public int a45() { return 45; }
        public int a46() { return 46; }
        public int a47() { return 47; }
        public int a48() { return 48; }
        public int a49() { return 49; }
        public int a50() { return 50; }
        public int a51() { return 51; }
        public int a52() { return 52; }
        public int a53() { return 53; }
        public int a54() { return 54; }
        public int a55() { return 55; }
        public int a56() { return 56; }
        public int a57() { return 57; }
        public int a58() { return 58; }
        public int a59() { return 59; }
        public int a60() { return 60; }
        public int a61() { return 61; }
        public int a62() { return 62; }
        public int a63() { return 63; }
        public int a64() { return 64; }
        public int a65() { return 65; }
        public int a66() { return 66; }
        public int a67() { return 67; }
        public int a68() { return 68; }
        public int a69() { return 69; }
        public int a70() { return 70; }
        public int a71() { return 71; }
        public int a72() { return 72; }
        public int a73() { return 73; }
        public int a74() { return 74; }
        public int a75() { return 75; }
        public int a76() { return 76; }
        public int a77() { return 77; }
        public int a78() { return 78; }
        public int a79() { return 79; }
        public int a80() { return 80; }
        public int a81() { return 81; }
        public int a82() { return 82; }
        public int a83() { return 83; }
        public int a84() { return 84; }
        public int a85() { return 85; }
        public int a86() { return 86; }
        public int a87() { return 87; }
        public int a88() { return 88; }
        public int a89() { return 89; }
        public int a90() { return 90; }
        public int a91() { return 91; }
        public int a92() { return 92; }
        public int a93() { return 93; }
        public int a94() { return 94; }
        public int a95() { return 95; }
        public int a96() { return 96; }
        public int a97() { return 97; }
        public int a98() { return 98; }
        public int a99() { return 99; }
        public int a100() { return 100; }
        public int a101() { return 101; }
        public int a102() { return 102; }
        public int a103() { return 103; }
        public int a104() { return 104; }
        public int a105() { return 105; }
        public int a106() { return 106; }
        public int a107() { return 107; }
        public int a108() { return 108; }
        public int a109() { return 109; }
        public int a110() { return 110; }
        public int a111() { return 111; }
        public int a112() { return 112; }
        public int a113() { return 113; }
        public int a114() { return 114; }
        public int a115() { return 115; }
        public int a116() { return 116; }
        public int a117() { return 117; }
        public int a118() { return 118; }
        public int a119() { return 119; }
        public int a120() { return 120; }
        public int a121() { return 121; }
        public int a122() { return 122; }
        public int a123() { return 123; }
        public int a124() { return 124; }
        public int a125() { return 125; }
        public int a126() { return 126; }
        public int a127() { return 127; }
        public int a128() { return 128; }
        public int a129() { return 129; }
        public int a130() { return 130; }
        public int a131() { return 131; }
        public int a132() { return 132; }
        public int a133() { return 133; }
        public int a134() { return 134; }
        public int a135() { return 135; }
        public int a136() { return 136; }
        public int a137() { return 137; }
        public int a138() { return 138; }
        public int a139() { return 139; }
        public int a140() { return 140; }
        public int a141() { return 141; }
        public int a142() { return 142; }
        public int a143() { return 143; }
        public int a144() { return 144; }
        public int a145() { return 145; }
        public int a146() { return 146; }
        public int a147() { return 147; }
        public int a148() { return 148; }
        public int a149() { return 149; }
        public int a150() { return 150; }
        public int a151() { return 151; }
        public int a152() { return 152; }
        public int a153() { return 153; }
        public int a154() { return 154; }
        public int a155() { return 155; }
        public int a156() { return 156; }
        public int a157() { return 157; }
        public int a158() { return 158; }
        public int a159() { return 159; }
        public int a160() { return 160; }
        public int a161() { return 161; }
        public int a162() { return 162; }
        public int a163() { return 163; }
        public int a164() { return 164; }
        public int a165() { return 165; }
        public int a166() { return 166; }
        public int a167() { return 167; }
        public int a168() { return 168; }
        public int a169() { return 169; }
        public int a170() { return 170; }
        public int a171() { return 171; }
        public int a172() { return 172; }
        public int a173() { return 173; }
        public int a174() { return 174; }
        public int a175() { return 175; }
        public int a176() { return 176; }
        public int a177() { return 177; }
        public int a178() { return 178; }
        public int a179() { return 179; }
        public int a180() { return 180; }
        public int a181() { return 181; }
        public int a182() { return 182; }
        public int a183() { return 183; }
        public int a184() { return 184; }
        public int a185() { return 185; }
        public int a186() { return 186; }
        public int a187() { return 187; }
        public int a188() { return 188; }
        public int a189() { return 189; }
        public int a190() { return 190; }
        public int a191() { return 191; }
        public int a192() { return 192; }
        public int a193() { return 193; }
        public int a194() { return 194; }
        public int a195() { return 195; }
        public int a196() { return 196; }
        public int a197() { return 197; }
        public int a198() { return 198; }
        public int a199() { return 199; }
        public int a200() { return 200; }
        public int a201() { return 201; }
        public int a202() { return 202; }
        public int a203() { return 203; }
        public int a204() { return 204; }
        public int a205() { return 205; }
        public int a206() { return 206; }
        public int a207() { return 207; }
        public int a208() { return 208; }
        public int a209() { return 209; }
        public int a210() { return 210; }
        public int a211() { return 211; }
        public int a212() { return 212; }
        public int a213() { return 213; }
        public int a214() { return 214; }
        public int a215() { return 215; }
        public int a216() { return 216; }
        public int a217() { return 217; }
        public int a218() { return 218; }
        public int a219() { return 219; }
        public int a220() { return 220; }
        public int a221() { return 221; }
        public int a222() { return 222; }
        public int a223() { return 223; }
        public int a224() { return 224; }
        public int a225() { return 225; }
        public int a226() { return 226; }
        public int a227() { return 227; }
        public int a228() { return 228; }
        public int a229() { return 229; }
        public int a230() { return 230; }
        public int a231() { return 231; }
        public int a232() { return 232; }
        public int a233() { return 233; }
        public int a234() { return 234; }
        public int a235() { return 235; }
        public int a236() { return 236; }
        public int a237() { return 237; }
        public int a238() { return 238; }
        public int a239() { return 239; }
        public int a240() { return 240; }
        public int a241() { return 241; }
        public int a242() { return 242; }
        public int a243() { return 243; }
        public int a244() { return 244; }
        public int a245() { return 245; }
        public int a246() { return 246; }
        public int a247() { return 247; }
        public int a248() { return 248; }
        public int a249() { return 249; }
        public int a250() { return 250; }
        public int a251() { return 251; }
        public int a252() { return 252; }
        public int a253() { return 253; }
        public int a254() { return 254; }
        public int a255() { return 255; }
        public int a256() { return 256; }
        public int a257() { return 257; }
        public int a258() { return 258; }
        public int a259() { return 259; }
        public int a260() { return 260; }
        public int a261() { return 261; }
        public int a262() { return 262; }
        public int a263() { return 263; }
        public int a264() { return 264; }
        public int a265() { return 265; }
        public int a266() { return 266; }
        public int a267() { return 267; }
        public int a268() { return 268; }
        public int a269() { return 269; }
        public int a270() { return 270; }
        public int a271() { return 271; }
        public int a272() { return 272; }
        public int a273() { return 273; }
        public int a274() { return 274; }
        public int a275() { return 275; }
        public int a276() { return 276; }
        public int a277() { return 277; }
        public int a278() { return 278; }
        public int a279() { return 279; }
        public int a280() { return 280; }
        public int a281() { return 281; }
        public int a282() { return 282; }
        public int a283() { return 283; }
        public int a284() { return 284; }
        public int a285() { return 285; }
        public int a286() { return 286; }
        public int a287() { return 287; }
        public int a288() { return 288; }
        public int a289() { return 289; }
        public int a290() { return 290; }
        public int a291() { return 291; }
        public int a292() { return 292; }
        public int a293() { return 293; }
        public int a294() { return 294; }
        public int a295() { return 295; }
        public int a296() { return 296; }
        public int a297() { return 297; }
        public int a298() { return 298; }
        public int a299() { return 299; }
    }
}
