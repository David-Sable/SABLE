% Run the authors' TVL 2018 pipeline (tv2018.m body without plotting/file output) on a raw
% 32 kHz stereo float32 file and write long-term loudness per 1 ms frame. Test-only (MATH §10.2).
% Usage (octave-cli): run_tvl2018('<tvl2018 dir>', '<in.f32>', '<out.txt>', dBMax)
function run_tvl2018(tvlDir, inFile, outFile, dBMax)
  shimDir = fileparts(mfilename('fullpath'));
  addpath(genpath(tvlDir));
  addpath(shimDir, '-begin');   % our hann.m / get_A.m shadow the originals
  fid = fopen(inFile, 'rb'); v = fread(fid, Inf, 'float32'); fclose(fid);
  s = reshape(v, 2, []).'; Fs = 32000;
  load(fullfile(tvlDir, 'transfer functions', 'ff_32000.mat'));
  out(:,1) = conv(vecCoefficients, s(:,1)); out(:,2) = conv(vecCoefficients, s(:,2));
  s = out(1025:end-1024, :);
  [L, R] = FilteredSignal2MonauralInstantaneousSpecificLoudness(s, Fs, dBMax);
  SL = InstantaneousSpecificLoudness2ShortTermSpecificLoudness(L);
  SR = InstantaneousSpecificLoudness2ShortTermSpecificLoudness(R);
  nL = zeros(size(SL,1),1); nR = nL;
  for i = 1:size(SL,1)
    [~, nL(i), nR(i)] = MonauralSpecificLoudness2BinauralLoudness025(SL(i,:), SR(i,:));
  end
  ltl = ShortTermLoudness2LongTermLoudness(nL) + ShortTermLoudness2LongTermLoudness(nR);
  fid = fopen(outFile, 'wt'); fprintf(fid, '%.10g\n', ltl); fclose(fid);
end
