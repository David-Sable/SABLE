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
  CHECK(separate(flowing, "{\"A\":{\"complete\":false,\"tracks\":[]}}"));          // album not fully analysed
  CHECK(separate(flowing, albums, "track"));                                           // Always track
  CHECK(separate(item(analysis(-8, -8, -8, -6, -9, -9), 3) + "," + item(analysis(-16, -16, -16, -6, -15, -15), 4), albums));  // no duration data
}

TEST_CASE("plan: contour headroom lowers the cap; learned offsets decay and stay bounded (MATH §19, §24.4)") {
  const std::string capped = analysis(-20, -20, -20, -2, -20, -20);  // intent +6, cap +1
  std::string in = ctx("{\"analysis\":" + capped + "}");
  in.insert(1, "\"contourHeadroomDb\":2.6,");
  CHECK(field(sable::planQueueJson(in), "gainDb") == doctest::Approx(1.0 - 2.6).epsilon(1e-12));

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

TEST_CASE("learn: event update, environment filter and clamp (MATH §19.1-19.2)") {
  const std::string a = sable::learnUpdateJson("{\"deltaDb\":-3,\"trackDb\":0,\"trackDays\":10,\"artistDb\":0,\"artistDays\":10,\"nowDays\":10}");
  CHECK(field(a, "trackDb") == doctest::Approx(-1.5));
  CHECK(field(a, "artistDb") == doctest::Approx(-0.75));
  CHECK(field(a, "omega") == 1.0);
  // Same sign and similar size as the previous event: weighted 0.25.
  const std::string b = sable::learnUpdateJson("{\"deltaDb\":-3,\"prevDeltaDb\":-4,\"trackDb\":0,\"trackDays\":10,\"artistDb\":0,\"artistDays\":10,\"nowDays\":10}");
  CHECK(field(b, "omega") == 0.25);
  CHECK(field(b, "trackDb") == doctest::Approx(-0.375));
  // Opposite sign: full weight. Clamp at +-2.
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":3,\"prevDeltaDb\":-3,\"nowDays\":0}"), "omega") == 1.0);
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":-12,\"trackDb\":-1.9,\"trackDays\":5,\"nowDays\":5}"), "trackDb") == -2.0);
  CHECK(sable::learnUpdateJson("{}") == "{\"error\":\"bad input\"}");
}
