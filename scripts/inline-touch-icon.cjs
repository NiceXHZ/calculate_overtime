// Keep the editable HTML and PNG separate; installed pages receive the image
// directly in their head so the iOS share sheet needs no extra icon request.
function inlineTouchIcon(html, png) {
  const links = html.match(
    /<link\b(?=[^>]*\brel="apple-touch-icon")[^>]*>/g,
  );
  if (links?.length !== 1 || !/\bhref="[^"]*"/.test(links[0])) {
    throw new Error("Expected exactly one apple-touch-icon link with an href.");
  }
  const href = "data:image/png;base64," + png.toString("base64");
  return html.replace(
    links[0],
    links[0].replace(/\bhref="[^"]*"/, `href="${href}"`),
  );
}

module.exports = { inlineTouchIcon };
