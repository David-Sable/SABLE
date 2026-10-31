// Queue planner (MATH §17.1, §18, §21, §22): exact checks of every path through planQueueJson.
#include <charconv>
#include <cmath>
#include <string>
#include <vector>

#include "doctest.h"
#include "sable/intent.h"
#include "sable/pipeline.h"
#include "sable/plan.h"
#include "signals.h"

namespace {

// Pulls the n-th occurrence of "key":<number> out of a JSON string (test helper only).
double field(const std::string& js, const std::string& key, int n = 0) {
  std::size_t p = 0;
  for (int i = 0; i <= n; ++i) {
    p = js.find("\"" + key + "\":", p);
    REQUIRE(p != std::string::npos);
    p += key.size() + 3;
  }
  double v = 0.0;
  std::from_chars(js.data() + p, js.data() + js.size(), v);
  return v;
}

std::string analysis(double li, double anchor, double ple, double tp, double open, double end, const char* character = "steady",
                     bool fadeIn = false, bool fadeOut = false) {
  auto n = [](double v) { return std::to_string(v); };
  return "{\"integratedLufs\":" + n(li) + ",\"truePeakDbtp\":" + n(tp) + ",\"shortFile\":false,\"decodeClipped\":false,\"anchorLufs\":" +
         n(anchor) + ",\"anchorPle\":" + n(ple) + ",\"anchorSec\":120,\"character\":\"" + character + "\",\"openLufs\":" + n(open) +
         ",\"endLufs\":" + n(end) + ",\"fadeIn\":" + (fadeIn ? "true" : "false") + ",\"fadeOut\":" + (fadeOut ? "true" : "false") +
         ",\"anchorBlockPower\":[" + n(std::pow(10.0, (anchor + 0.691) / 10.0)) + "]}";
}

std::string ctx(const std::string& items, const std::string& albums = "{}", const char* mode = "auto", double cal = 0.0) {
  return "{\"targetLufs\":-14,\"intimacyLu\":1,\"ceilingDbtp\":-1,\"limiter\":false,\"outputCalDb\":" + std::to_string(cal) +
         ",\"albumMode\":\"" + mode + "\",\"albums\":" + albums + ",\"items\":[" + items + "]}";
}

}  // namespace

TEST_CASE("plan: track gain equals the cached Standard decision of the full pipeline") {
  auto a = sigs::multiSine(44100, 2, 30.0, 60.0, 12000.0, 40, -20.0, 3);
  sable::TrackPipeline p(44100, 2, 0, false);
  p.pushPass1(a.x.data(), a.frames());
  if (p.endPass1()) do {
      p.beginPass2();
      p.pushPass2(a.x.data(), a.frames());
    } while (p.endPass2());
  const std::string js = p.resultJson();
  const std::string out = sable::planQueueJson(ctx("{\"analysis\":" + js + ",\"manual\":true}"));
  const double cached = field(js, "gainDb", 0);  // "standard":{"gainDb":..}
  CHECK(field(out, "gainDb") == cached);          // exact: same rules, exact JSON round trip
  CHECK(field(out, "manualGainDb") == cached);
}

TEST_CASE("plan: album world shares one gain; song world and pending albums use track mode") {
  const std::string t1 = analysis(-10, -10, -10, -0.5, -12, -12), t2 = analysis(-16, -16, -16, -6, -18, -18);
  const std::string albums = "{\"A\":{\"complete\":true,\"tracks\":[" + t1 + "," + t2 + "]}}";
  const std::string items = "{\"analysis\":" + t1 + ",\"album\":\"A\",\"albumIntent\":true},{\"analysis\":" + t2 +
                            ",\"album\":\"A\",\"albumIntent\":true}";
  const std::string out = sable::planQueueJson(ctx(items, albums));
  CHECK(field(out, "gainDb", 0) == field(out, "gainDb", 1));
  CHECK(out.find("\"albumRun\":true") != std::string::npos);
  // Album cap: the loudest member's TP (-0.5) bounds the shared gain to -0.5 dB.
  CHECK(field(out, "gainDb", 0) <= -0.5);

  // Song world (tapped): each track in its own world.
  const std::string song = "{\"analysis\":" + t1 + ",\"album\":\"A\",\"albumIntent\":false},{\"analysis\":" + t2 +
                           ",\"album\":\"A\",\"albumIntent\":false}";
  const std::string s = sable::planQueueJson(ctx(song, albums));
  CHECK(field(s, "baseDb", 0) == doctest::Approx(-4.0).epsilon(1e-12));
  CHECK(field(s, "baseDb", 1) == doctest::Approx(2.0).epsilon(1e-12));
  CHECK(s.find("\"albumRun\":true") == std::string::npos);

  // "Always album" setting overrides the intent; "always track" disables album gain.
  CHECK(sable::planQueueJson(ctx(song, albums, "album")).find("\"albumRun\":true") != std::string::npos);
  CHECK(sable::planQueueJson(ctx(items, albums, "track")).find("\"albumRun\":true") == std::string::npos);

  // Album not fully analysed: track mode + ALBUM_PENDING.
  const std::string pending = sable::planQueueJson(ctx(items, "{\"A\":{\"complete\":false,\"tracks\":[]}}"));
  CHECK(pending.find("ALBUM_PENDING") != std::string::npos);
  CHECK(field(pending, "baseDb", 0) == doctest::Approx(-4.0).epsilon(1e-12));
}

TEST_CASE("plan: seam smoothing stays within 0.5 dB, follows the applied chain, and is skipped for manual starts") {
  // prev: anchor -14 -> g 0; ends at -10. cur: anchor -14 -> g 0; opens at -20. Ideal = -10 + 0 - (-20) = +10 -> +0.5.
  const std::string prev = analysis(-14, -14, -14, -20, -14, -10), cur = analysis(-14, -14, -14, -20, -20, -14);
  const std::string out = sable::planQueueJson(ctx("{\"analysis\":" + prev + ",\"manual\":true},{\"analysis\":" + cur + "}"));
  CHECK(field(out, "gainDb", 1) == doctest::Approx(0.5).epsilon(1e-12));
  CHECK(field(out, "manualGainDb", 1) == doctest::Approx(0.0).epsilon(1e-12));
  CHECK(out.find("\"SEAM\"") != std::string::npos);
  // Fade-out on prev: no seam.
  const std::string faded = analysis(-14, -14, -14, -20, -14, -10, "steady", false, true);
  CHECK(field(sable::planQueueJson(ctx("{\"analysis\":" + faded + "},{\"analysis\":" + cur + "}")), "gainDb", 1) == 0.0);
  // A JIT item breaks the chain (no seam data).
  const std::string jitPrev = "{\"jit\":{\"jitLufs\":-10,\"jitTpDbtp\":-3}}";
  CHECK(field(sable::planQueueJson(ctx(jitPrev + ",{\"analysis\":" + cur + "}")), "gainDb", 1) == 0.0);
}

TEST_CASE("plan: JIT, provisional, calibration never breaks the cap") {
  const std::string out = sable::planQueueJson(ctx("{\"jit\":{\"jitLufs\":-8,\"jitTpDbtp\":-3}},{\"analysis\":null}"));
  CHECK(field(out, "gainDb", 0) == doctest::Approx(-6.0).epsilon(1e-12));  // min(-14+8, -1-(-3+1)) = min(-6, +1)
  CHECK(out.find("\"source\":\"jit\"") != std::string::npos);
  CHECK(field(out, "gainDb", 1) == doctest::Approx(-6.0).epsilon(1e-12));  // §22.1 T + 8
  CHECK(out.find("\"source\":\"provisional\"") != std::string::npos);
  // JIT peak cap: TP -0.5 + 1 margin -> cap -1.5.
  CHECK(field(sable::planQueueJson(ctx("{\"jit\":{\"jitLufs\":-20,\"jitTpDbtp\":-0.5}}")), "gainDb") == doctest::Approx(-1.5).epsilon(1e-12));
  // +3 dB output calibration on a track already at its cap: stays at the cap (§21 step 4).
  const std::string capped = analysis(-20, -20, -20, -2, -20, -20);  // intent +6, cap +1
  CHECK(field(sable::planQueueJson(ctx("{\"analysis\":" + capped + "}", "{}", "auto", 3.0)), "gainDb") == doctest::Approx(1.0).epsilon(1e-12));
}

TEST_CASE("plan: a provisional or quick-estimate gain obeys the ceiling and the contour's headroom (MATH-1.40)") {
  // Provisional: a 4 dB lesson plus +3 calibration on -6 would be +1 dB on a song whose peak nobody has measured.
  const std::string prov = sable::planQueueJson(ctx("{\"learnedDb\":4}", "{}", "auto", 3.0));
  CHECK(field(prov, "capDb") == doctest::Approx(-1.0).epsilon(1e-12));
  CHECK(field(prov, "gainDb") == doctest::Approx(-1.0).epsilon(1e-12));
  const auto quiet = [](const std::string& item, double headroom) {
    return "{\"targetLufs\":-19,\"ceilingDbtp\":-1,\"contourHeadroomDb\":" + std::to_string(headroom) + ",\"albumMode\":\"auto\",\"items\":[" + item + "]}";
  };
  // Quick estimate in Quiet: -30 LUFS wants +11; peak -10 + 1 margin caps it at +8, or +5.4 once the 2.6 dB contour counts.
  const std::string jit = "{\"jit\":{\"jitLufs\":-30,\"jitTpDbtp\":-10}}";
  CHECK(field(sable::planQueueJson(quiet(jit, 0.0)), "gainDb") == doctest::Approx(8.0).epsilon(1e-12));
  CHECK(field(sable::planQueueJson(quiet(jit, 2.6)), "gainDb") == doctest::Approx(5.4).epsilon(1e-12));
  CHECK(field(sable::planQueueJson(quiet("{}", 2.6)), "capDb") == doctest::Approx(-3.6).epsilon(1e-12));
}

TEST_CASE("plan: an item that has started keeps its seam when its predecessor leaves the window (MATH-1.40)") {
  const auto track = [](double end, double open) { return analysis(-14, -14, -14, -20, open, end); };
  // Three songs in a row: a ends loud, so b is seamed up by 0.5; c opens 0.3 under where b ends. Ideal for c:
  // b's end + b's gain - c's open = -14 + 0.5 - (-14.3) = 0.8, held at +0.5 with the full chain.
  const std::string a = track(-10, -14), b = track(-14, -20), c = track(-14, -14.3);
  const auto item = [](const std::string& x, const std::string& extra = "") { return "{\"analysis\":" + x + extra + "}"; };
  const std::string chain = sable::planQueueJson(ctx(item(a, ",\"manual\":true") + "," + item(b) + "," + item(c)));
  CHECK(field(chain, "gainDb", 1) == doctest::Approx(0.5).epsilon(1e-12));
  CHECK(field(chain, "seamDb", 1) == doctest::Approx(0.5).epsilon(1e-12));
  // b and c play on with a gone: without b's own seam c would be worked out from b at 0 dB.
  CHECK(field(sable::planQueueJson(ctx(item(b) + "," + item(c))), "gainDb", 1) == doctest::Approx(0.3).epsilon(1e-9));
  CHECK(field(sable::planQueueJson(ctx(item(b, ",\"seamDb\":0.5") + "," + item(c))), "gainDb", 1) == doctest::Approx(0.5).epsilon(1e-12));
  // The kept seam holds on the item itself, under its cap, and within the 0.5 dB bound whatever is sent.
  const std::string kept = sable::planQueueJson(ctx(item(b, ",\"seamDb\":9")));
  CHECK(field(kept, "gainDb") == doctest::Approx(0.5).epsilon(1e-12));
  CHECK(kept.find("\"SEAM\"") != std::string::npos);
  CHECK(field(kept, "manualGainDb") == doctest::Approx(0.0).epsilon(1e-12));
  CHECK(field(sable::planQueueJson(ctx(item(analysis(-14, -14, -14, -1, -14, -14), ",\"seamDb\":0.5"))), "gainDb") == doctest::Approx(0.0).epsilon(1e-12));
}

TEST_CASE("plan: malformed input is rejected, output is deterministic") {
  CHECK(sable::planQueueJson("{\"items\":[") == "{\"error\":\"bad input\"}");
  CHECK(sable::planQueueJson("[]") == "{\"error\":\"bad input\"}");
  const std::string in = ctx("{\"jit\":{\"jitLufs\":-9.123456789,\"jitTpDbtp\":-2}}");
  CHECK(sable::planQueueJson(in) == sable::planQueueJson(in));
}

TEST_CASE("plan: song-world neighbours that flow into each other share the album gain (MATH §17.3)") {
  // A 200 s track; `silence` seconds of it are silent. Loud (-8) and quiet (-16) tracks of one album.
  auto timed = [](std::string a, double silence) {
    a.pop_back();
    return a + ",\"frames\":9600000,\"sampleRateHz\":48000,\"nonSilentSec\":" + std::to_string(200.0 - silence) + "}";
  };
  const std::string t1 = timed(analysis(-8, -8, -8, -6, -9, -9), 0.2), t2 = timed(analysis(-16, -16, -16, -6, -15, -15), 0.3);
  const std::string albums = "{\"A\":{\"complete\":true,\"tracks\":[" + t1 + "," + t2 + "]}}";
  auto item = [](const std::string& a, int no, const char* extra = "", bool intent = false) {
    return "{\"analysis\":" + a + ",\"album\":\"A\",\"albumIntent\":" + (intent ? "true" : "false") + ",\"disc\":1,\"trackNo\":" +
           std::to_string(no) + extra + "}";
  };
  const std::string flowing = item(t1, 3, ",\"manual\":true") + "," + item(t2, 4);
  const std::string out = sable::planQueueJson(ctx(flowing, albums));
  CHECK(field(out, "gainDb", 0) == field(out, "gainDb", 1));       // one gain across the join
  CHECK(out.find("\"FLOW\"") != std::string::npos);
  CHECK(out.find("\"SEAM\"") == std::string::npos);
  const std::string played = sable::planQueueJson(ctx(item(t1, 3, "", true) + "," + item(t2, 4, "", true), albums));
  CHECK(played.find("\"FLOW\"") == std::string::npos);            // Play album: an album run already
  CHECK(field(out, "gainDb", 0) == field(played, "gainDb", 0));      // the flow run plays at Play album's gain

  auto separate = [&](const std::string& items, const std::string& alb, const char* mode = "auto") {
    const std::string o = sable::planQueueJson(ctx(items, alb, mode));
    return o.find("\"FLOW\"") == std::string::npos && field(o, "gainDb", 0) != field(o, "gainDb", 1);
  };
  CHECK(separate(item(t1, 3) + "," + item(t2, 5), albums));                          // not the next track
  CHECK(separate(item(t1, 3) + "," + item(timed(analysis(-16, -16, -16, -6, -15, -15), 2.0), 4), albums));  // a real pause
  CHECK(separate(item(t1, 3) + "," + item(timed(analysis(-16, -16, -16, -6, -15, -15, "steady", true), 0.3), 4), albums));  // fade-in
  // Edge silences (analysis 1.9): a pause in the middle of a track no longer blocks flow; a gap at the join does.
  auto edged = [&](std::string a, double lead, double trail, double mid) {
    a.pop_back();
    return a + ",\"frames\":9600000,\"sampleRateHz\":48000,\"nonSilentSec\":" + std::to_string(200.0 - lead - trail - mid) +
           ",\"leadSilenceSec\":" + std::to_string(lead) + ",\"trailSilenceSec\":" + std::to_string(trail) + "}";
  };
  const std::string e1 = edged(analysis(-8, -8, -8, -6, -9, -9), 0.1, 0.1, 6.0), e2 = edged(analysis(-16, -16, -16, -6, -15, -15), 0.2, 0.1, 5.0);
  const std::string e3 = edged(analysis(-16, -16, -16, -6, -15, -15), 1.5, 0.1, 0.0);
  const std::string ealb = "{\"A\":{\"complete\":true,\"tracks\":[" + e1 + "," + e2 + "]}}";
  const std::string eout = sable::planQueueJson(ctx(item(e1, 3, ",\"manual\":true") + "," + item(e2, 4), ealb));
  CHECK(eout.find("\"FLOW\"") != std::string::npos);
  CHECK(field(eout, "gainDb", 0) == field(eout, "gainDb", 1));
  CHECK(separate(item(e1, 3) + "," + item(e3, 4), "{\"A\":{\"complete\":true,\"tracks\":[" + e1 + "," + e3 + "]}}"));  // 1.6 s gap at the join
  CHECK(separate(flowing, "{\"A\":{\"complete\":false,\"tracks\":[]}}"));          // album not fully analysed
  CHECK(separate(flowing, albums, "track"));                                           // Always track
  CHECK(separate(item(analysis(-8, -8, -8, -6, -9, -9), 3) + "," + item(analysis(-16, -16, -16, -6, -15, -15), 4), albums));  // no duration data
}

TEST_CASE("plan: a PLE from an anchor under 3 s of sound is not trusted (MATH §16.2.3)") {
  // A fragment: anchor -21.7 LUFS, PLE -64 (noise), loud enough peaks not to cap. Its gain follows the meter alone.
  std::string frag = analysis(-20.9, -21.7, -63.9, -20, -21, -21);
  const auto at = frag.find("\"anchorSec\":120");
  REQUIRE(at != std::string::npos);
  frag.replace(at, 15, "\"anchorSec\":0.6");
  const std::string out = sable::planQueueJson(ctx("{\"analysis\":" + frag + "}"));
  CHECK(field(out, "gainDb") == doctest::Approx(-14.0 + 21.7).epsilon(1e-12));
  CHECK(out.find("\"BS1770_ONLY\"") != std::string::npos);
  CHECK(out.find("\"PERCEPTUAL_BOUND\"") == std::string::npos);
  // The same figures with a 120 s anchor: the PLE is used (and bounded to anchor - 3).
  const std::string body = sable::planQueueJson(ctx("{\"analysis\":" + analysis(-20.9, -21.7, -63.9, -20, -21, -21) + "}"));
  CHECK(field(body, "gainDb") == doctest::Approx(-14.0 + 24.7).epsilon(1e-12));
}

TEST_CASE("plan: contour headroom lowers the cap; learned offsets decay and stay bounded (MATH §19, §24.4)") {
  const std::string capped = analysis(-20, -20, -20, -2, -20, -20);  // intent +6, cap +1
  std::string in = ctx("{\"analysis\":" + capped + "}");
  in.insert(1, "\"contourHeadroomDb\":2.6,");
  CHECK(field(sable::planQueueJson(in), "gainDb") == doctest::Approx(1.0 - 2.6).epsilon(1e-12));
  // MATH-1.29: the headroom is bounded (an input of 1e30 dB once produced a gain of -3.4e38 dB in the fuzz run).
  std::string huge = ctx("{\"analysis\":" + capped + "}");
  huge.insert(1, "\"contourHeadroomDb\":1e30,");
  CHECK(field(sable::planQueueJson(huge), "gainDb") == doctest::Approx(1.0 - 12.0).epsilon(1e-12));

  const std::string mid = analysis(-14, -14, -14, -20, -14, -14);  // g 0, cap +19
  // Track offset -1.6 set 30 days ago -> -0.8 now; artist -1 (1 day old) ignored until 2 tracks have taught it.
  std::string l = ctx("{\"analysis\":" + mid + ",\"learn\":{\"trackDb\":-1.6,\"trackDays\":0,\"artistDb\":-1,\"artistDays\":29,\"artistTracks\":1}}");
  l.insert(1, "\"nowDays\":30,");
  const std::string out = sable::planQueueJson(l);
  CHECK(field(out, "learnedDb") == doctest::Approx(-0.8).epsilon(1e-9));
  CHECK(field(out, "gainDb") == doctest::Approx(-0.8).epsilon(1e-9));
  std::string l2 = l;
  l2.replace(l2.find("\"artistTracks\":1"), 16, "\"artistTracks\":2");
  CHECK(field(sable::planQueueJson(l2), "learnedDb") == doctest::Approx(-0.8 - std::pow(2.0, -1.0 / 30.0)).epsilon(1e-9));
}

TEST_CASE("plan: a song's own lesson beats the artist's; the artist's needs agreeing songs (MATH-1.23 §19.2)") {
  const std::string mid = analysis(-14, -14, -14, -20, -14, -14);  // g 0, cap +19
  auto learned = [&](const std::string& learn) {
    return field(sable::planQueueJson(ctx("{\"analysis\":" + mid + ",\"learn\":" + learn + "}")), "learnedDb");
  };
  // Owner's session: turned "its dark and hell is hot" down (-0.48) while the artist stood at +1.68 from another song.
  // Before 1.23 the sum made the song 1.2 dB LOUDER after a turn-down; now its own lesson rules.
  CHECK(learned("{\"trackDb\":-0.48,\"trackTaught\":true,\"artistDb\":1.68,\"artistTrackDbs\":[2.0,-0.48]}") == doctest::Approx(-0.48));
  // An untaught song by that artist: the two taught songs disagree, so nothing is applied.
  CHECK(learned("{\"artistDb\":1.68,\"artistTrackDbs\":[2.0,-0.48]}") == 0.0);
  // Two taught songs that agree: the artist's offset applies to the others.
  CHECK(learned("{\"artistDb\":-0.9,\"artistTrackDbs\":[-1.5,-0.4]}") == doctest::Approx(-0.9));
  CHECK(learned("{\"artistDb\":-0.9,\"artistTrackDbs\":[-1.5]}") == 0.0);  // one song is not an artist
}

TEST_CASE("learn: event update, environment filter and clamp (MATH §19.1-19.2)") {
  const std::string a = sable::learnUpdateJson("{\"deltaDb\":-3,\"trackDb\":0,\"trackDays\":10,\"artistDb\":0,\"artistDays\":10,\"nowDays\":10}");
  CHECK(field(a, "trackDb") == doctest::Approx(-1.5));
  CHECK(field(a, "artistDb") == doctest::Approx(-0.75));
  CHECK(field(a, "omega") == 1.0);
  // Same sign and similar size as the previous event: weighted 0.25.
  const std::string b = sable::learnUpdateJson("{\"deltaDb\":-3,\"prevDeltaDb\":-4,\"prevDays\":9.996,\"trackDb\":0,\"trackDays\":10,\"artistDb\":0,\"artistDays\":10,\"nowDays\":10}");
  CHECK(field(b, "omega") == 0.25);
  CHECK(field(b, "trackDb") == doctest::Approx(-0.375));
  // MATH-1.23: only a recent previous event (within 10 min) suggests an environment change. Owner's session: STFU! turned
  // down 30 min after the last event of an earlier session was weighted 0.25; now full weight. No time stored: full weight.
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":-3.83,\"prevDeltaDb\":-3.83,\"prevDays\":9.979,\"nowDays\":10}"), "omega") == 1.0);
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":-3.83,\"prevDeltaDb\":-3.83,\"nowDays\":10}"), "omega") == 1.0);
  // MATH-1.24, owner's session: "Hustle Bones" turned down 2 steps (-24.67 -> -32.33 dB), the volume put back to -24.67,
  // then "JRJRJR" turned down 1 step 3.5 min later. The put-back shows both presses were about the songs: full weight.
  const std::string jr = "{\"deltaDb\":-3.83,\"prevDeltaDb\":-7.67,\"prevDays\":9.9976,\"prevEndDb\":-32.33,\"startDb\":-24.67,\"nowDays\":10}";
  CHECK(field(sable::learnUpdateJson(jr), "omega") == 1.0);
  CHECK(field(sable::learnUpdateJson(jr), "trackDb") == doctest::Approx(-1.915));
  // Volume left where the previous press put it: still an environment change.
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":-3.83,\"prevDeltaDb\":-7.6,\"prevDays\":9.9976,\"prevEndDb\":-32.26,\"startDb\":-32.26,\"nowDays\":10}"), "omega") == 0.25);
  // Opposite sign: full weight. Clamp at +-2.
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":3,\"prevDeltaDb\":-3,\"nowDays\":0}"), "omega") == 1.0);
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":-12,\"trackDb\":-1.9,\"trackDays\":5,\"nowDays\":5}"), "trackDb") == -2.0);
  CHECK(sable::learnUpdateJson("{}") == "{\"error\":\"bad input\"}");
  // MATH-1.36, owner's drive: a tap is never the room, whatever came before it or how the level got there.
  const std::string tapAfterTap = sable::learnUpdateJson("{\"tap\":true,\"deltaDb\":-2,\"prevDeltaDb\":-2,\"prevDays\":9.998,\"prevEndDb\":0,\"startDb\":0,\"jumpDays\":9.998,\"jumpEndDb\":0,\"nowDays\":10}");
  CHECK(field(tapAfterTap, "omega") == 1.0);
  CHECK(field(tapAfterTap, "trackDb") == doctest::Approx(-1.0));
  CHECK(field(tapAfterTap, "artistDb") == doctest::Approx(-0.5));
  // MATH-1.38: a tap needs no probation. The first tap on a new song is heard in full and confirms it.
  CHECK(tapAfterTap.find("\"trackConfirmed\":true") != std::string::npos);
  // MATH-1.39: taps go on to +-4 dB, 1 dB each; a tap past the first two is still a tap (no probation, no environment weight).
  auto tapAt = [&](double from, double delta, double nowD) {
    return sable::learnUpdateJson("{\"tap\":true,\"deltaDb\":" + std::to_string(delta) + ",\"trackDb\":" + std::to_string(from) +
                                  ",\"trackDays\":10,\"trackFirstDays\":10,\"trackConfirmed\":true,\"artistDb\":-0.5,\"artistDays\":10,\"artistFirstDays\":10,\"nowDays\":" + std::to_string(nowD) + "}");
  };
  CHECK(field(tapAt(-1.0, -2, 10.0001), "trackDb") == doctest::Approx(-2.0).epsilon(1e-3));
  CHECK(field(tapAt(-2.0, -2, 10.0001), "trackDb") == doctest::Approx(-3.0).epsilon(1e-3));
  CHECK(field(tapAt(-3.0, -2, 10.0001), "trackDb") == doctest::Approx(-4.0).epsilon(1e-3));
  CHECK(field(tapAt(2.5, 2, 10.0001), "trackDb") == doctest::Approx(3.5).epsilon(1e-3));
  // The artist never goes past 2 dB, whatever the song does: -1.9 plus a tap of -0.5 stays at -2.
  CHECK(field(sable::learnUpdateJson("{\"tap\":true,\"deltaDb\":-12,\"trackDb\":-3,\"trackDays\":10,\"trackFirstDays\":5,\"trackConfirmed\":true,\"artistDb\":-1.9,\"artistDays\":10,\"nowDays\":10}"), "artistDb") == doctest::Approx(-2.0));
  // At the 4 dB ceiling a tap the same way changes nothing, not even the artist, and the app says so; the first tap back is heard in full.
  const std::string atLimit = "\"trackDb\":-4,\"trackDays\":10,\"trackFirstDays\":10,\"trackConfirmed\":true,\"artistDb\":-1,\"artistDays\":10,\"artistFirstDays\":10,\"nowDays\":10.0001}";
  CHECK(sable::learnUpdateJson("{\"tap\":true,\"deltaDb\":-2," + atLimit).find("\"ignored\":\"limit\"") != std::string::npos);
  CHECK(field(sable::learnUpdateJson("{\"tap\":true,\"deltaDb\":2," + atLimit), "trackDb") == doctest::Approx(-3.0).epsilon(1e-3));
  // MATH-1.39: a volume press keeps its 2 dB ceiling, and a song a tap took beyond it is neither deepened nor pulled over by a press.
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":-12,\"trackDb\":-1.9,\"trackDays\":5,\"trackFirstDays\":0,\"trackConfirmed\":true,\"nowDays\":5}"), "trackDb") == doctest::Approx(-2.0));
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":-12,\"trackDb\":-3,\"trackDays\":5,\"trackFirstDays\":0,\"trackConfirmed\":true,\"nowDays\":5}"), "trackDb") == doctest::Approx(-3.0));  // not deepened
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":2,\"trackDb\":-3,\"trackDays\":5,\"trackFirstDays\":0,\"trackConfirmed\":true,\"nowDays\":5}"), "trackDb") == doctest::Approx(-2.0));  // lessened: -3 + 1
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":12,\"trackDb\":-3,\"trackDays\":5,\"trackFirstDays\":0,\"trackConfirmed\":true,\"nowDays\":5}"), "trackDb") == doctest::Approx(2.0));  // carried over: only to +2
  // Whatever was stored, no event leaves a song beyond 4 dB (a hand-edited -9 is brought to -4 by a press, not kept).
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":-2,\"trackDb\":-9,\"trackDays\":5,\"trackFirstDays\":0,\"trackConfirmed\":true,\"nowDays\":5}"), "trackDb") == doctest::Approx(-4.0));
  // A song a press left unconfirmed at -1 (heard as -1): a tap takes it to -2 at once, no waiting for 12 h.
  CHECK(field(sable::learnUpdateJson("{\"tap\":true,\"deltaDb\":-2,\"trackDb\":-1,\"trackDays\":10,\"trackFirstDays\":10,\"trackConfirmed\":false,\"nowDays\":10.01}"), "trackDb") == doctest::Approx(-2.0).epsilon(1e-3));
  // A press stored past what was heard (-2, unconfirmed): a tap back moves from where the song stands, to -1.
  CHECK(field(sable::learnUpdateJson("{\"tap\":true,\"deltaDb\":2,\"trackDb\":-2,\"trackDays\":10,\"trackFirstDays\":10,\"trackConfirmed\":false,\"nowDays\":10.001}"), "trackDb") == doctest::Approx(-1.0).epsilon(1e-3));
  // A press is unchanged by all this: still a hint, heard at most 1 dB until a repeat on another day (MATH-1.30).
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":-2,\"trackDb\":-1,\"trackDays\":10,\"trackFirstDays\":10,\"trackConfirmed\":false,\"nowDays\":10}"), "trackDb") == doctest::Approx(-2.0));
}

TEST_CASE("plan: absurd targets and ceilings are bounded (MATH-1.29)") {
  const std::string quiet = analysis(-26, -26, -26, -12, -26, -26);  // intent +12 dB at -14, true peak -12 dBTP
  auto gain = [&](const std::string& head) {
    std::string in = ctx("{\"analysis\":" + quiet + "}");
    in.insert(1, head);
    return field(sable::planQueueJson(in), "gainDb");
  };
  CHECK(gain("\"ceilingDbtp\":-1e300,") == doctest::Approx(-10.0 + 12.0));  // ceiling bounded to -10 dBTP: cap +2
  CHECK(gain("\"ceilingDbtp\":1e300,") <= 12.0 + 1e-9);  // ceiling bounded to 0 dBTP: cap +12, intent +12
  CHECK(std::abs(gain("\"targetLufs\":-1e300,")) < 100.0);
  CHECK(std::abs(gain("\"targetLufs\":1e300,")) < 100.0);
}

TEST_CASE("plan: an unknown true peak caps at full scale, not at nothing (MATH-1.29, §16.4)") {
  // A quiet song (intent +12 dB) whose peak could not be measured (a non-finite sample): before 1.29 the cap was +inf.
  std::string quiet = analysis(-26, -26, -26, -12, -26, -26);
  CHECK(field(sable::planQueueJson(ctx("{\"analysis\":" + quiet + "}")), "gainDb") == doctest::Approx(11.0));  // measured -12 dBTP
  std::string noTp = quiet;
  const std::size_t p = noTp.find("\"truePeakDbtp\":");
  REQUIRE(p != std::string::npos);
  noTp.replace(p, noTp.find(',', p) - p, "\"truePeakDbtp\":null");
  const std::string out = sable::planQueueJson(ctx("{\"analysis\":" + noTp + "}"));
  CHECK(field(out, "gainDb") == doctest::Approx(-1.0));  // ceiling -1 dBTP minus a 0 dBTP peak
  CHECK(field(out, "capDb") == doctest::Approx(-1.0));
  // A quick estimate without a peak: ceiling -1 minus (0 + 1 dB margin).
  CHECK(field(sable::planQueueJson(ctx("{\"jit\":{\"jitLufs\":-26}}")), "gainDb") == doctest::Approx(-2.0));
  // Digital silence still has nothing to protect.
  const std::string silent = "{\"integratedLufs\":null,\"truePeakDbtp\":null,\"shortFile\":false,\"decodeClipped\":false}";
  CHECK(field(sable::planQueueJson(ctx("{\"analysis\":" + silent + "}")), "gainDb") == doctest::Approx(0.0));
}

TEST_CASE("learn: mutes, big jumps and presses explained by the section (MATH-1.27)") {
  auto ignored = [](const std::string& out, const char* why) { return out == std::string("{\"ignored\":\"") + why + "\"}"; };
  // A mute (either end) or more than 12 dB is the room, not the song; exactly 12 still counts.
  CHECK(ignored(sable::learnUpdateJson("{\"deltaDb\":-60,\"nowDays\":1}"), "jump"));
  CHECK(ignored(sable::learnUpdateJson("{\"deltaDb\":-3.83,\"muted\":true,\"nowDays\":1}"), "jump"));
  CHECK(ignored(sable::learnUpdateJson("{\"deltaDb\":12.5,\"nowDays\":1}"), "jump"));
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":-12,\"nowDays\":1}"), "trackDb") == -2.0);
  // Quiet intro (10 dB under the anchor) turned up one step: the section explains all of it.
  const std::string quiet = "{\"deltaDb\":3.83,\"anchorLufs\":-10,\"heardLufs\":-20,\"nowDays\":1}";
  CHECK(ignored(sable::learnUpdateJson(quiet), "section"));
  // 3 dB under the anchor: 1 dB band, 2 dB explained, 1.83 dB left for the song.
  const std::string some = sable::learnUpdateJson("{\"deltaDb\":3.83,\"anchorLufs\":-10,\"heardLufs\":-13,\"nowDays\":1}");
  CHECK(field(some, "effectiveDb") == doctest::Approx(1.83));
  CHECK(field(some, "trackDb") == doctest::Approx(0.915));
  // A loud opening (3 dB over the anchor) turned down one step: same, mirrored.
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":-3.83,\"anchorLufs\":-10,\"heardLufs\":-7,\"nowDays\":1}"), "effectiveDb") == doctest::Approx(-1.83));
  // Against the section's tendency (quiet intro turned DOWN) or inside the band: the press counts in full.
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":-3.83,\"anchorLufs\":-10,\"heardLufs\":-20,\"nowDays\":1}"), "trackDb") == doctest::Approx(-1.915));
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":3.83,\"anchorLufs\":-10,\"heardLufs\":-10.8,\"nowDays\":1}"), "trackDb") == doctest::Approx(1.915));
  // Without the song's figures (no analysis yet) nothing changes.
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":3.83,\"nowDays\":1}"), "trackDb") == doctest::Approx(1.915));
  // MATH-1.28, owner's session on the JBL: the volume jumped -17 -> 0 dB (ignored), and 4 min later "Bye Storm" was turned down
  // 5.67 dB from that level. The level had not settled: weight 0.25 (was 1.0, track -2.00 and artist -2.00).
  const std::string bye = "{\"deltaDb\":-5.67,\"startDb\":0,\"jumpDays\":9.9972,\"jumpEndDb\":0,\"nowDays\":10}";
  CHECK(field(sable::learnUpdateJson(bye), "omega") == 0.25);
  CHECK(field(sable::learnUpdateJson(bye), "trackDb") == doctest::Approx(-0.708).epsilon(1e-3));
  // The volume put back to a level of its own, an old jump (> 10 min), or no jump: full weight.
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":-5.67,\"startDb\":-17,\"jumpDays\":9.9972,\"jumpEndDb\":0,\"nowDays\":10}"), "omega") == 1.0);
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":-5.67,\"startDb\":0,\"jumpDays\":9.98,\"jumpEndDb\":0,\"nowDays\":10}"), "omega") == 1.0);
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":-5.67,\"startDb\":0,\"nowDays\":10}"), "omega") == 1.0);
  CHECK(sable::sectionAdjustedDelta(4.0, 2.5) == doctest::Approx(1.5));
  CHECK(sable::sectionAdjustedDelta(2.0, 2.5) == 0.0);
  CHECK(sable::sectionAdjustedDelta(-4.0, 2.5) == -4.0);
}

TEST_CASE("learn: a first lesson is a hint until another day confirms it (MATH-1.30 §19.2)") {
  auto has = [](const std::string& s, const char* part) { return s.find(part) != std::string::npos; };
  // A song never taught: the lesson starts unconfirmed, stamped with now.
  const std::string first = sable::learnUpdateJson("{\"deltaDb\":3.83,\"nowDays\":10}");
  CHECK(has(first, "\"trackConfirmed\":false"));
  CHECK(field(first, "trackFirstDays") == 10.0);
  // The same way again the same day: still a hint. Another day (12 h or more): confirmed.
  const std::string same = "{\"deltaDb\":3.83,\"trackDb\":1.9,\"trackDays\":10,\"trackFirstDays\":10,\"trackConfirmed\":false,\"nowDays\":10.1}";
  CHECK(has(sable::learnUpdateJson(same), "\"trackConfirmed\":false"));
  const std::string later = "{\"deltaDb\":3.83,\"trackDb\":1.9,\"trackDays\":10,\"trackFirstDays\":10,\"trackConfirmed\":false,\"nowDays\":10.6}";
  CHECK(has(sable::learnUpdateJson(later), "\"trackConfirmed\":true"));
  // The opposite way on another day does not confirm: it cancels.
  const std::string opposite = "{\"deltaDb\":-3.83,\"trackDb\":1.9,\"trackDays\":10,\"trackFirstDays\":10,\"trackConfirmed\":false,\"nowDays\":10.6}";
  CHECK(has(sable::learnUpdateJson(opposite), "\"trackConfirmed\":false"));
  // A lesson stored before 1.30 has no such fields and is trusted.
  CHECK(has(sable::learnUpdateJson("{\"deltaDb\":3.83,\"trackDb\":1.5,\"trackDays\":5,\"nowDays\":10}"), "\"trackConfirmed\":true"));
  // A pre-1.30 artist is trusted: it dates from before its last event.
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":3.83,\"artistDb\":0.5,\"artistDays\":8,\"nowDays\":10}"), "artistFirstDays") == 7.5);

  // Planning: an unconfirmed lesson applies at most 1 dB; a confirmed one in full.
  const std::string mid = analysis(-14, -14, -14, -20, -14, -14);
  auto learned = [&](const std::string& learn) {
    return field(sable::planQueueJson(ctx("{\"analysis\":" + mid + ",\"learn\":" + learn + "}")), "learnedDb");
  };
  CHECK(learned("{\"trackDb\":2.0,\"trackTaught\":true,\"trackConfirmed\":false}") == doctest::Approx(1.0));
  CHECK(learned("{\"trackDb\":-1.9,\"trackTaught\":true,\"trackConfirmed\":false}") == doctest::Approx(-1.0));
  CHECK(learned("{\"trackDb\":-0.4,\"trackTaught\":true,\"trackConfirmed\":false}") == doctest::Approx(-0.4));
  CHECK(learned("{\"trackDb\":2.0,\"trackTaught\":true,\"trackConfirmed\":true}") == doctest::Approx(2.0));
  CHECK(learned("{\"trackDb\":2.0,\"trackTaught\":true}") == doctest::Approx(2.0));  // before 1.30
  // MATH-1.39: a song a tap took past 2 dB is applied as it stands, and nothing above 4 dB can be applied.
  CHECK(learned("{\"trackDb\":-3.0,\"trackTaught\":true,\"trackConfirmed\":true}") == doctest::Approx(-3.0));
  CHECK(learned("{\"trackDb\":9.0,\"trackTaught\":true,\"trackConfirmed\":true}") == doctest::Approx(4.0));
  CHECK(learned("{\"trackDb\":3.0,\"trackTaught\":true,\"trackConfirmed\":false}") == doctest::Approx(1.0));  // unconfirmed (a press) is still held to 1
  // The artist's offset waits for events on two different days (owner's session: two songs in five minutes taught
  // Danny Brown +1.16 dB for every other song).
  const std::string art = "\"artistDb\":-0.9,\"artistTrackDbs\":[-1.5,-0.4],\"artistFirstDays\":10,";
  CHECK(learned("{" + art + "\"artistDays\":10.01}") == 0.0);
  CHECK(learned("{" + art + "\"artistDays\":10.6}") != 0.0);
}

TEST_CASE("learn: a press up on a song its peak cap holds teaches nothing (MATH-1.31 §19.1)") {
  auto has = [](const std::string& s, const char* part) { return s.find(part) != std::string::npos; };
  // At the cap (no room): up is ignored, down counts, and so does an unknown room.
  CHECK(has(sable::learnUpdateJson("{\"deltaDb\":3.83,\"headroomDb\":0.0,\"nowDays\":10}"), "\"ignored\":\"capped\""));
  CHECK(has(sable::learnUpdateJson("{\"deltaDb\":3.83,\"headroomDb\":0.3,\"nowDays\":10}"), "\"ignored\":\"capped\""));
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":-3.83,\"headroomDb\":0.0,\"nowDays\":10}"), "trackDb") == doctest::Approx(-1.915));
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":3.83,\"headroomDb\":2.0,\"nowDays\":10}"), "trackDb") == doctest::Approx(1.915));
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":3.83,\"nowDays\":10}"), "trackDb") == doctest::Approx(1.915));
  // The section rule can still reduce an up press to nothing first; the cap only looks at a press that would count.
  CHECK(has(sable::learnUpdateJson("{\"deltaDb\":3.83,\"headroomDb\":5,\"anchorLufs\":-10,\"heardLufs\":-20,\"nowDays\":10}"), "\"ignored\""));
}
