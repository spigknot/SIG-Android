K = "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"
t = open(K).read().split("\n")
for i, ln in enumerate(t):
    if "static bool is_mergeable_mul_mat(" in ln:
        print("=== L%d: %s ===" % (i+1, ln.strip()[:100]))
        for j in range(i+1, min(i+30, len(t))):
            s = t[j]
            print("  ", s[:140])
            if s.strip() == "}" and j > i+3: break
